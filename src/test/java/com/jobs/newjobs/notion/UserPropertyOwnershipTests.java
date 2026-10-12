package com.jobs.newjobs.notion;

import static com.jobs.newjobs.notion.application.NotionSchemaValidator.APPLIED_AT;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.FIRST_SYNCED_AT;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.STATUS;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.SYNC_OPERATION_ID;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.USER_NOTES;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.notion.application.NotionGateway.UpdateResult;
import com.jobs.newjobs.notion.application.NotionPageMapper;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.notion.domain.NotionPropertyType;
import com.jobs.newjobs.notion.domain.NotionPropertyValue;
import com.jobs.newjobs.notion.infrastructure.HttpNotionGateway;
import com.jobs.newjobs.sync.application.NotionUpdateService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class UserPropertyOwnershipTests {
	private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
	private static final UUID ORIGINAL_OPERATION = UUID.fromString("db0ab0f6-3fc8-48b0-962a-f90664e870e7");

	private HttpServer server;
	private URI baseUrl;
	private JsonMapper json;

	@BeforeEach
	void startServer() throws IOException {
		json = JsonMapper.builder().build();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.start();
		baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	@AfterEach
	void stopServer() {
		if (server != null) server.stop(0);
	}

	@Test
	void preservesUserSentinelsExtraPropertiesAndImmutableMarkersAfterAmbiguousUpdate() {
		AtomicInteger patches = new AtomicInteger();
		AtomicReference<JsonNode> sentProperties = new AtomicReference<>();
		server.createContext("/v1/pages/page-1", exchange -> {
			if (exchange.getRequestMethod().equals("PATCH")) {
				patches.incrementAndGet();
				sentProperties.set(json.readTree(read(exchange)).path("properties"));
				respond(exchange, 503, "{\"code\":\"service_unavailable\"}");
				return;
			}
			respond(exchange, 200, pageWithSentinels(sentProperties.get()));
		});

		UpdateResult result = updateService().update("page-1", job("Changed Company"));

		assertThat(result).isInstanceOf(UpdateResult.Reconciled.class);
		assertThat(patches).hasValue(1);
		JsonNode patch = sentProperties.get();
		assertThat(patch.has(STATUS)).isFalse();
		assertThat(patch.has(USER_NOTES)).isFalse();
		assertThat(patch.has(APPLIED_AT)).isFalse();
		assertThat(patch.has(FIRST_SYNCED_AT)).isFalse();
		assertThat(patch.has(SYNC_OPERATION_ID)).isFalse();
		assertThat(patch.has("Personal Priority")).isFalse();

		var snapshot = ((UpdateResult.Reconciled) result).snapshot();
		assertThat(snapshot.properties()).containsEntry(STATUS,
			new NotionPropertyValue.Option(NotionPropertyType.STATUS, "Interview"));
		assertThat(snapshot.properties()).containsEntry(USER_NOTES,
			new NotionPropertyValue.Text(NotionPropertyType.RICH_TEXT, "sentinel notes"));
		assertThat(snapshot.properties()).containsEntry(APPLIED_AT, new NotionPropertyValue.Date(NOW.minusSeconds(3_600)));
		assertThat(snapshot.properties()).containsEntry("Personal Priority", new NotionPropertyValue.Numeric(BigDecimal.TEN));
		assertThat(snapshot.properties()).containsEntry(FIRST_SYNCED_AT, new NotionPropertyValue.Date(NOW.minusSeconds(86_400)));
		assertThat(snapshot.properties()).containsEntry(SYNC_OPERATION_ID,
			new NotionPropertyValue.Text(NotionPropertyType.RICH_TEXT, ORIGINAL_OPERATION.toString()));
	}

	@Test
	void retriesSameAtomicPatchOnlyAfterConclusiveRereadShowsItWasNotApplied() {
		AtomicInteger patches = new AtomicInteger();
		AtomicReference<String> firstBody = new AtomicReference<>();
		AtomicReference<String> secondBody = new AtomicReference<>();
		server.createContext("/v1/pages/page-2", exchange -> {
			if (exchange.getRequestMethod().equals("GET")) {
				respond(exchange, 200, pageWithSentinels(json.createObjectNode()));
				return;
			}
			String body = read(exchange);
			if (patches.incrementAndGet() == 1) {
				firstBody.set(body);
				respond(exchange, 503, "{\"code\":\"service_unavailable\"}");
			}
			else {
				secondBody.set(body);
				respond(exchange, 200, pageWithSentinels(json.readTree(body).path("properties")));
			}
		});

		UpdateResult result = updateService().update("page-2", job("Changed Company"));

		assertThat(result).isInstanceOf(UpdateResult.Reconciled.class);
		assertThat(patches).hasValue(2);
		assertThat(secondBody.get()).isEqualTo(firstBody.get());
		assertThat(firstBody.get()).contains("Last Synced At");
	}

	@Test
	void doesNotRetryWhenTheReconciliationReadIsAlsoAmbiguous() {
		AtomicInteger patches = new AtomicInteger();
		AtomicInteger reads = new AtomicInteger();
		server.createContext("/v1/pages/page-3", exchange -> {
			if (exchange.getRequestMethod().equals("GET")) reads.incrementAndGet();
			else patches.incrementAndGet();
			respond(exchange, 503, "{\"code\":\"service_unavailable\"}");
		});

		UpdateResult result = updateService().update("page-3", job("Changed Company"));

		assertThat(result).isInstanceOf(UpdateResult.Ambiguous.class);
		assertThat(patches).hasValue(1);
		assertThat(reads).hasValue(3);
	}

	@Test
	void blankSourceValuesAndRecentSearchAbsenceCannotEraseOrMarkAJobUnavailable() {
		var patch = new NotionPageMapper(new JobIdentityCanonicalizer()).updatePatch(job("  "), NOW);

		assertThat(patch.properties()).doesNotContainKeys("Company", STATUS, USER_NOTES, APPLIED_AT,
			FIRST_SYNCED_AT, SYNC_OPERATION_ID, "Availability", "Archived");
	}

	private NotionUpdateService updateService() {
		NotionProperties properties = new NotionProperties(baseUrl, "2026-03-11", "token",
			"database-id", "source-id", Duration.ofSeconds(1), Duration.ofSeconds(2));
		var gateway = new HttpNotionGateway(HttpClient.newHttpClient(), json, properties);
		return new NotionUpdateService(gateway, new NotionPageMapper(new JobIdentityCanonicalizer()),
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static NormalizedJob job(String company) {
		return new NormalizedJob(JobSource.HIRING_CAFE, "job-7", "Backend Engineer", company,
			"Brazil", "Remote", List.of("Full Time"), null, NOW.minusSeconds(60), "Java", "Senior",
			BigDecimal.valueOf(5), List.of("Build services"), List.of("Java"),
			URI.create("https://example.test/jobs/7"));
	}

	private String pageWithSentinels(JsonNode ownedProperties) throws IOException {
		ObjectNode properties = json.createObjectNode();
		ownedProperties.properties().forEach(entry -> {
			ObjectNode responseValue = (ObjectNode) entry.getValue().deepCopy();
			String type = responseValue.propertyNames().iterator().next();
			responseValue.put("type", type);
			properties.set(entry.getKey(), responseValue);
		});
		properties.set(STATUS, option("status", "Interview"));
		properties.set(USER_NOTES, text("rich_text", "sentinel notes"));
		properties.set(APPLIED_AT, date(NOW.minusSeconds(3_600)));
		properties.set("Personal Priority", json.createObjectNode().put("type", "number").put("number", 10));
		properties.set(FIRST_SYNCED_AT, date(NOW.minusSeconds(86_400)));
		properties.set(SYNC_OPERATION_ID, text("rich_text", ORIGINAL_OPERATION.toString()));
		ObjectNode page = json.createObjectNode();
		page.put("id", "page-1").put("archived", false).put("in_trash", false).set("properties", properties);
		return json.writeValueAsString(page);
	}

	private ObjectNode option(String type, String value) {
		ObjectNode node = json.createObjectNode().put("type", type);
		node.putObject(type).put("name", value);
		return node;
	}

	private ObjectNode text(String type, String value) {
		ObjectNode node = json.createObjectNode().put("type", type);
		node.putArray(type).addObject().put("plain_text", value);
		return node;
	}

	private ObjectNode date(Instant value) {
		ObjectNode node = json.createObjectNode().put("type", "date");
		node.putObject("date").put("start", value.toString());
		return node;
	}

	private static String read(HttpExchange exchange) throws IOException {
		return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
