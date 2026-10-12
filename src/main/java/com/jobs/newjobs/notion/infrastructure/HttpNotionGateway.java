package com.jobs.newjobs.notion.infrastructure;

import static com.jobs.newjobs.notion.application.NotionSchemaValidator.CANONICAL_URL;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.EXTERNAL_JOB_ID;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.FIRST_SYNCED_AT;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.NEW_STATUS;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.SOURCE;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.STATUS;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.SYNC_OPERATION_ID;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.notion.application.NotionGateway;
import com.jobs.newjobs.notion.application.NotionPageMapper;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.notion.domain.NotionFailure;
import com.jobs.newjobs.notion.domain.NotionFailure.Code;
import com.jobs.newjobs.notion.domain.NotionPageDraft;
import com.jobs.newjobs.notion.domain.NotionPagePatch;
import com.jobs.newjobs.notion.domain.NotionPageSnapshot;
import com.jobs.newjobs.notion.domain.NotionPropertyType;
import com.jobs.newjobs.notion.domain.NotionPropertyValue;
import com.jobs.newjobs.notion.domain.NotionQuotaEvidence;
import com.jobs.newjobs.notion.domain.NotionSchema;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class HttpNotionGateway implements NotionGateway {
	private static final int MAX_READ_ATTEMPTS = 3;
	private static final int MAX_PAGES = 100;
	private static final int MAX_RESULTS = 10_000;
	private static final int MAX_PAYLOAD_BYTES = 500 * 1_024;

	private final HttpClient httpClient;
	private final JsonMapper jsonMapper;
	private final NotionProperties properties;

	@Autowired
	public HttpNotionGateway(JsonMapper jsonMapper, NotionProperties properties) {
		this(HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER).build(), jsonMapper, properties);
	}

	public HttpNotionGateway(HttpClient httpClient, JsonMapper jsonMapper, NotionProperties properties) {
		this.httpClient = httpClient;
		this.jsonMapper = jsonMapper;
		this.properties = properties;
	}

	@Override
	public SchemaResult retrieveSchema() {
		TransportResult result = read(requestBuilder("/v1/data_sources/" + segment(properties.dataSourceId())).GET().build());
		if (result.failure() != null) {
			return new SchemaResult.Failed(result.failure());
		}
		try {
			JsonNode root = parse(result.response().body());
			Map<String, NotionSchema.PropertyDefinition> definitions = new LinkedHashMap<>();
			for (Map.Entry<String, JsonNode> property : root.path("properties").properties()) {
				JsonNode value = property.getValue();
				NotionPropertyType type = NotionPropertyType.fromApi(text(value.path("type")));
				List<String> options = new ArrayList<>();
				for (JsonNode option : value.path(apiType(type)).path("options")) {
					String name = text(option.path("name"));
					if (name != null) {
						options.add(name);
					}
				}
				definitions.put(property.getKey(), new NotionSchema.PropertyDefinition(
					text(value.path("id")), property.getKey(), type, options));
			}
			String id = text(root.path("id"));
			return new SchemaResult.Available(new NotionSchema(id == null ? properties.dataSourceId() : id, definitions));
		}
		catch (RuntimeException exception) {
			return new SchemaResult.Failed(failure(Code.MALFORMED_RESPONSE, result.response(),
				"Notion schema response was malformed"));
		}
	}

	@Override
	public IdentityResult queryByIdentity(JobSource source, String externalId, URI canonicalUrl) {
		if (source == null || ((externalId == null || externalId.isBlank()) && canonicalUrl == null)) {
			throw new IllegalArgumentException("source and at least one identity are required");
		}
		Map<String, NotionPageSnapshot> matches = new LinkedHashMap<>();
		if (externalId != null && !externalId.isBlank()) {
			PageReadResult pages = query(identityFilter(source, EXTERNAL_JOB_ID, "rich_text", externalId.trim()));
			if (pages.failure() != null || pages.incompleteReason() != null) {
				return new IdentityResult.Incomplete(reason(pages), pages.failure());
			}
			pages.pages().forEach(page -> matches.put(page.pageId(), page));
		}
		if (canonicalUrl != null) {
			PageReadResult pages = query(identityFilter(source, CANONICAL_URL, "url", canonicalUrl.toString()));
			if (pages.failure() != null || pages.incompleteReason() != null) {
				return new IdentityResult.Incomplete(reason(pages), pages.failure());
			}
			pages.pages().forEach(page -> matches.put(page.pageId(), page));
		}
		if (matches.isEmpty()) {
			return new IdentityResult.None();
		}
		if (matches.size() == 1) {
			return new IdentityResult.One(matches.values().iterator().next());
		}
		return new IdentityResult.Conflict(List.copyOf(matches.values()));
	}

	@Override
	public QuotaResult scanAutomationCreations(Instant fromInclusive) {
		if (fromInclusive == null) {
			throw new IllegalArgumentException("quota window start is required");
		}
		ObjectNode filter = jsonMapper.createObjectNode();
		ArrayNode and = filter.putArray("and");
		and.add(propertyFilter(SYNC_OPERATION_ID, "rich_text", "is_not_empty", true));
		and.add(propertyFilter(FIRST_SYNCED_AT, "date", "on_or_after", fromInclusive.toString()));
		PageReadResult pages = query(filter);
		if (pages.failure() != null || pages.incompleteReason() != null) {
			return new QuotaResult.Incomplete(reason(pages), pages.failure());
		}

		List<NotionQuotaEvidence> evidence = new ArrayList<>();
		try {
			for (NotionPageSnapshot page : pages.pages()) {
				String operation = stringValue(page.properties().get(SYNC_OPERATION_ID));
				Instant firstSynced = dateValue(page.properties().get(FIRST_SYNCED_AT));
				if (operation == null || firstSynced == null) {
					return new QuotaResult.Incomplete("automation ownership evidence is missing or malformed", null);
				}
				evidence.add(new NotionQuotaEvidence(page.pageId(), UUID.fromString(operation), firstSynced, page.archived()));
			}
		}
		catch (IllegalArgumentException exception) {
			return new QuotaResult.Incomplete("automation ownership evidence is missing or malformed", null);
		}
		return new QuotaResult.Complete(evidence);
	}

	@Override
	public CreateResult createPage(NotionPageDraft draft, UUID operationId) {
		if (draft == null || operationId == null) {
			throw new IllegalArgumentException("draft and operation ID are required");
		}
		NotionFailure validationFailure = validateCreateDraft(draft, operationId);
		if (validationFailure != null) {
			return new CreateResult.ReliablyNotSent(validationFailure);
		}
		ObjectNode body = jsonMapper.createObjectNode();
		body.putObject("parent").put("data_source_id", properties.dataSourceId());
		body.set("properties", propertiesNode(draft.properties()));
		ArrayNode children = body.putArray("children");
		for (String chunk : draft.descriptionChunks()) {
			ObjectNode paragraph = children.addObject();
			paragraph.put("object", "block");
			paragraph.put("type", "paragraph");
			ArrayNode richText = paragraph.putObject("paragraph").putArray("rich_text");
			richText.addObject().put("type", "text").putObject("text").put("content", chunk);
		}
		SerializedBody serialized = serialize(body);
		if (serialized.failure() != null) {
			return new CreateResult.ReliablyNotSent(serialized.failure());
		}
		try {
			HttpResponse<String> response = httpClient.send(jsonRequest("/v1/pages", serialized.body()).POST(
				HttpRequest.BodyPublishers.ofString(serialized.body())).build(), HttpResponse.BodyHandlers.ofString());
			if (successful(response.statusCode())) {
				NotionPageSnapshot snapshot = parsePage(parse(response.body()));
				return new CreateResult.Confirmed(snapshot.pageId(), snapshot);
			}
			NotionFailure failure = failure(codeFor(response.statusCode()), response, safeProviderReason(response));
			return response.statusCode() >= 500
				? new CreateResult.Ambiguous(failure)
				: new CreateResult.ReliablyNotSent(failure);
		}
		catch (IOException exception) {
			return new CreateResult.Ambiguous(new NotionFailure(Code.TRANSPORT, null, null,
				"Notion create delivery could not be determined"));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new CreateResult.Ambiguous(new NotionFailure(Code.INTERRUPTED, null, null,
				"Notion create was interrupted after send began"));
		}
		catch (RuntimeException exception) {
			return new CreateResult.Ambiguous(new NotionFailure(Code.MALFORMED_RESPONSE, null, null,
				"Notion create response did not conclusively identify a page"));
		}
	}

	@Override
	public UpdateResult updatePage(String pageId, NotionPagePatch patch) {
		if (pageId == null || pageId.isBlank() || patch == null) {
			throw new IllegalArgumentException("page ID and patch are required");
		}
		if (patch.properties().keySet().stream().anyMatch(property -> !NotionPageMapper.isUpdateAllowed(property))) {
			return new UpdateResult.ReliablyNotSent(new NotionFailure(Code.VALIDATION, null, null,
				"Notion patch contains a property outside the backend update allowlist"));
		}
		ObjectNode body = jsonMapper.createObjectNode();
		body.set("properties", propertiesNode(patch.properties()));
		SerializedBody serialized = serialize(body);
		if (serialized.failure() != null) {
			return new UpdateResult.ReliablyNotSent(serialized.failure());
		}
		try {
			HttpResponse<String> response = httpClient.send(jsonRequest("/v1/pages/" + segment(pageId), serialized.body())
				.method("PATCH", HttpRequest.BodyPublishers.ofString(serialized.body())).build(),
				HttpResponse.BodyHandlers.ofString());
			if (successful(response.statusCode())) {
				return new UpdateResult.Reconciled(parsePage(parse(response.body())));
			}
			NotionFailure failure = failure(codeFor(response.statusCode()), response, safeProviderReason(response));
			return response.statusCode() >= 500 ? reconcileUpdate(pageId, patch, failure)
				: new UpdateResult.ReliablyNotSent(failure);
		}
		catch (IOException exception) {
			return reconcileUpdate(pageId, patch, new NotionFailure(Code.TRANSPORT, null, null,
				"Notion update requires read reconciliation"));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new UpdateResult.Ambiguous(new NotionFailure(Code.INTERRUPTED, null, null,
				"Notion update requires read reconciliation"));
		}
		catch (RuntimeException exception) {
			return new UpdateResult.Ambiguous(new NotionFailure(Code.MALFORMED_RESPONSE, null, null,
				"Notion update response requires read reconciliation"));
		}
	}

	private UpdateResult reconcileUpdate(String pageId, NotionPagePatch patch, NotionFailure ambiguousFailure) {
		TransportResult read = read(requestBuilder("/v1/pages/" + segment(pageId)).GET().build());
		if (read.failure() != null) {
			return new UpdateResult.Ambiguous(ambiguousFailure);
		}
		try {
			NotionPageSnapshot snapshot = parsePage(parse(read.response().body()));
			boolean matches = patch.properties().entrySet().stream()
				.allMatch(entry -> entry.getValue().equals(snapshot.properties().get(entry.getKey())));
			return matches ? new UpdateResult.Reconciled(snapshot) : new UpdateResult.Ambiguous(ambiguousFailure);
		}
		catch (RuntimeException exception) {
			return new UpdateResult.Ambiguous(ambiguousFailure);
		}
	}

	private static NotionFailure validateCreateDraft(NotionPageDraft draft, UUID operationId) {
		String marker = stringValue(draft.properties().get(SYNC_OPERATION_ID));
		String status = stringValue(draft.properties().get(STATUS));
		if (!operationId.toString().equals(marker) || !NEW_STATUS.equals(status)
			|| !(draft.properties().get(FIRST_SYNCED_AT) instanceof NotionPropertyValue.Date)) {
			return new NotionFailure(Code.VALIDATION, null, null,
				"Notion create requires matching immutable markers and Status=New");
		}
		if (draft.descriptionChunks().size() > 100 || draft.descriptionChunks().stream()
			.anyMatch(chunk -> chunk == null || chunk.isBlank() || chunk.length() > 2_000)) {
			return new NotionFailure(Code.PAYLOAD_LIMIT, null, null,
				"Notion description exceeds supported block limits");
		}
		for (NotionPropertyValue value : draft.properties().values()) {
			String text = stringValue(value);
			if (text != null && text.length() > 2_000) {
				return new NotionFailure(Code.PAYLOAD_LIMIT, null, null,
					"Notion property exceeds the supported text limit");
			}
		}
		return null;
	}

	private PageReadResult query(ObjectNode filter) {
		List<NotionPageSnapshot> pages = new ArrayList<>();
		Set<String> cursors = new LinkedHashSet<>();
		String cursor = null;
		for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
			ObjectNode body = jsonMapper.createObjectNode();
			body.put("page_size", 100);
			body.set("filter", filter);
			if (cursor != null) {
				body.put("start_cursor", cursor);
			}
			SerializedBody serialized = serialize(body);
			if (serialized.failure() != null) {
				return new PageReadResult(pages, null, serialized.failure());
			}
			TransportResult result = read(jsonRequest("/v1/data_sources/" + segment(properties.dataSourceId()) + "/query",
				serialized.body()).POST(HttpRequest.BodyPublishers.ofString(serialized.body())).build());
			if (result.failure() != null) {
				return new PageReadResult(pages, null, result.failure());
			}
			try {
				JsonNode root = parse(result.response().body());
				String incomplete = text(root.path("request_status").path("incomplete_reason"));
				if (incomplete != null) {
					return new PageReadResult(pages, incomplete, null);
				}
				if (!root.path("results").isArray()) {
					return new PageReadResult(pages, null, failure(Code.MALFORMED_RESPONSE, result.response(),
						"Notion query response omitted results"));
				}
				for (JsonNode page : root.path("results")) {
					pages.add(parsePage(page));
					if (pages.size() > MAX_RESULTS) {
						return new PageReadResult(pages, "bounded read limit exceeded", null);
					}
				}
				if (!root.path("has_more").booleanValue()) {
					return new PageReadResult(pages, null, null);
				}
				cursor = text(root.path("next_cursor"));
				if (cursor == null || !cursors.add(cursor)) {
					return new PageReadResult(pages, "missing or repeated pagination cursor", null);
				}
			}
			catch (RuntimeException exception) {
				return new PageReadResult(pages, null, failure(Code.MALFORMED_RESPONSE, result.response(),
					"Notion query response was malformed"));
			}
		}
		return new PageReadResult(pages, "bounded page limit exceeded", null);
	}

	private ObjectNode identityFilter(JobSource source, String identityProperty, String identityType, String value) {
		ObjectNode filter = jsonMapper.createObjectNode();
		ArrayNode and = filter.putArray("and");
		and.add(propertyFilter(SOURCE, "select", "equals", source.name()));
		and.add(propertyFilter(identityProperty, identityType, "equals", value));
		return filter;
	}

	private ObjectNode propertyFilter(String property, String type, String operator, Object value) {
		ObjectNode filter = jsonMapper.createObjectNode();
		filter.put("property", property);
		ObjectNode typed = filter.putObject(type);
		if (value instanceof Boolean booleanValue) {
			typed.put(operator, booleanValue);
		}
		else {
			typed.put(operator, String.valueOf(value));
		}
		return filter;
	}

	private ObjectNode propertiesNode(Map<String, NotionPropertyValue> values) {
		ObjectNode propertiesNode = jsonMapper.createObjectNode();
		values.forEach((name, value) -> propertiesNode.set(name, propertyNode(value)));
		return propertiesNode;
	}

	private ObjectNode propertyNode(NotionPropertyValue value) {
		ObjectNode node = jsonMapper.createObjectNode();
		switch (value) {
			case NotionPropertyValue.Text text -> {
				String type = apiType(text.type());
				ArrayNode array = node.putArray(type);
				array.addObject().put("type", "text").putObject("text").put("content", text.value());
			}
			case NotionPropertyValue.Option option -> node.putObject(apiType(option.type())).put("name", option.value());
			case NotionPropertyValue.Link link -> node.put("url", link.value());
			case NotionPropertyValue.Date date -> node.putObject("date").put("start", date.value().toString());
			case NotionPropertyValue.Options options -> {
				ArrayNode array = node.putArray("multi_select");
				options.values().forEach(option -> array.addObject().put("name", option));
			}
			case NotionPropertyValue.Flag flag -> node.put("checkbox", flag.value());
			case NotionPropertyValue.Numeric numeric -> node.put("number", numeric.value());
		}
		return node;
	}

	private NotionPageSnapshot parsePage(JsonNode page) {
		String pageId = text(page.path("id"));
		Map<String, NotionPropertyValue> values = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> property : page.path("properties").properties()) {
			NotionPropertyValue value = parseProperty(property.getValue());
			if (value != null) {
				values.put(property.getKey(), value);
			}
		}
		return new NotionPageSnapshot(pageId, page.path("archived").booleanValue(),
			page.path("in_trash").booleanValue(), values);
	}

	private NotionPropertyValue parseProperty(JsonNode property) {
		NotionPropertyType type = NotionPropertyType.fromApi(text(property.path("type")));
		return switch (type) {
			case TITLE, RICH_TEXT -> new NotionPropertyValue.Text(type, concatenateText(property.path(apiType(type))));
			case SELECT, STATUS -> new NotionPropertyValue.Option(type, text(property.path(apiType(type)).path("name")));
			case URL -> new NotionPropertyValue.Link(text(property.path("url")));
			case DATE -> new NotionPropertyValue.Date(parseInstant(text(property.path("date").path("start"))));
			case MULTI_SELECT -> new NotionPropertyValue.Options(names(property.path("multi_select")));
			case CHECKBOX -> new NotionPropertyValue.Flag(property.path("checkbox").booleanValue());
			case NUMBER -> property.path("number").isNumber()
				? new NotionPropertyValue.Numeric(property.path("number").decimalValue()) : null;
			case UNKNOWN -> null;
		};
	}

	private TransportResult read(HttpRequest request) {
		NotionFailure lastFailure = null;
		for (int attempt = 1; attempt <= MAX_READ_ATTEMPTS; attempt++) {
			try {
				HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
				if (successful(response.statusCode())) {
					return new TransportResult(response, null);
				}
				lastFailure = failure(codeFor(response.statusCode()), response, safeProviderReason(response));
				if (!retryable(response.statusCode()) || attempt == MAX_READ_ATTEMPTS) {
					return new TransportResult(null, lastFailure);
				}
				pause(retryDelay(response, attempt));
			}
			catch (IOException exception) {
				lastFailure = new NotionFailure(Code.TRANSPORT, null, null, "Notion read transport failed");
				if (attempt == MAX_READ_ATTEMPTS) {
					return new TransportResult(null, lastFailure);
				}
				try {
					pause(Duration.ofMillis(100L << (attempt - 1)));
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return new TransportResult(null, new NotionFailure(Code.INTERRUPTED, null, null,
						"Notion read was interrupted"));
				}
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return new TransportResult(null, new NotionFailure(Code.INTERRUPTED, null, null,
					"Notion read was interrupted"));
			}
		}
		return new TransportResult(null, lastFailure);
	}

	private HttpRequest.Builder requestBuilder(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
			.timeout(properties.requestTimeout())
			.header("Accept", "application/json")
			.header("Notion-Version", properties.apiVersion());
		if (properties.token() != null && !properties.token().isBlank()) {
			builder.header("Authorization", "Bearer " + properties.token());
		}
		return builder;
	}

	private HttpRequest.Builder jsonRequest(String path, String body) {
		return requestBuilder(path).header("Content-Type", "application/json");
	}

	private URI endpoint(String path) {
		String base = properties.baseUrl().toString();
		return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
	}

	private SerializedBody serialize(JsonNode body) {
		try {
			String json = jsonMapper.writeValueAsString(body);
			if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
				return new SerializedBody(null, new NotionFailure(Code.PAYLOAD_LIMIT, null, null,
					"Notion request exceeds the local payload limit"));
			}
			return new SerializedBody(json, null);
		}
		catch (JacksonException exception) {
			return new SerializedBody(null, new NotionFailure(Code.VALIDATION, null, null,
				"Notion request could not be serialized"));
		}
	}

	private JsonNode parse(String body) {
		try {
			return jsonMapper.readTree(body);
		}
		catch (JacksonException exception) {
			throw new IllegalArgumentException("invalid JSON", exception);
		}
	}

	private static String safeProviderReason(HttpResponse<String> response) {
		return switch (response.statusCode()) {
			case 400 -> "Notion rejected the request as invalid";
			case 401, 403 -> "Notion rejected authentication or authorization";
			case 404 -> "Notion resource was not found or not shared";
			case 409 -> "Notion reported a conflict";
			case 429 -> "Notion rate limit was reached";
			default -> response.statusCode() >= 500 ? "Notion service was unavailable" : "Notion request failed";
		};
	}

	private static NotionFailure failure(Code code, HttpResponse<?> response, String reason) {
		return new NotionFailure(code, response.statusCode(),
			response.headers().firstValue("x-request-id").orElse(null), reason);
	}

	private static Code codeFor(int status) {
		if (status == 401 || status == 403) return Code.AUTHENTICATION;
		if (status == 429) return Code.RATE_LIMITED;
		if (status >= 500) return Code.TRANSIENT_PROVIDER;
		return Code.PROVIDER_REJECTED;
	}

	private static boolean successful(int status) {
		return status >= 200 && status < 300;
	}

	private static boolean retryable(int status) {
		return status == 429 || status == 502 || status == 503 || status == 504;
	}

	private static Duration retryDelay(HttpResponse<?> response, int attempt) {
		return response.headers().firstValue("Retry-After").flatMap(HttpNotionGateway::seconds)
			.orElse(Duration.ofMillis(100L << (attempt - 1)));
	}

	private static java.util.Optional<Duration> seconds(String value) {
		try {
			long seconds = Long.parseLong(value.trim());
			return java.util.Optional.of(Duration.ofSeconds(Math.max(0, Math.min(seconds, 30))));
		}
		catch (NumberFormatException exception) {
			return java.util.Optional.empty();
		}
	}

	private static void pause(Duration delay) throws InterruptedException {
		Thread.sleep(delay);
	}

	private static String segment(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Notion data source/page ID is required");
		}
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String apiType(NotionPropertyType type) {
		return type.name().toLowerCase(java.util.Locale.ROOT);
	}

	private static String text(JsonNode node) {
		return node != null && node.isString() && !node.stringValue().isBlank() ? node.stringValue() : null;
	}

	private static Instant parseInstant(String value) {
		if (value == null) return null;
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException exception) {
			return null;
		}
	}

	private static String concatenateText(JsonNode values) {
		StringBuilder result = new StringBuilder();
		for (JsonNode value : values) {
			String plain = text(value.path("plain_text"));
			if (plain == null) {
				plain = text(value.path("text").path("content"));
			}
			if (plain != null) result.append(plain);
		}
		return result.toString();
	}

	private static List<String> names(JsonNode values) {
		List<String> names = new ArrayList<>();
		for (JsonNode value : values) {
			String name = text(value.path("name"));
			if (name != null) names.add(name);
		}
		return names;
	}

	private static String stringValue(NotionPropertyValue value) {
		return switch (value) {
			case NotionPropertyValue.Text text -> text.value();
			case NotionPropertyValue.Option option -> option.value();
			default -> null;
		};
	}

	private static Instant dateValue(NotionPropertyValue value) {
		return value instanceof NotionPropertyValue.Date date ? date.value() : null;
	}

	private static String reason(PageReadResult result) {
		return result.incompleteReason() != null ? result.incompleteReason()
			: result.failure() == null ? "Notion visibility was incomplete" : result.failure().reason();
	}

	private record TransportResult(HttpResponse<String> response, NotionFailure failure) { }
	private record SerializedBody(String body, NotionFailure failure) { }
	private record PageReadResult(List<NotionPageSnapshot> pages, String incompleteReason, NotionFailure failure) { }
}
