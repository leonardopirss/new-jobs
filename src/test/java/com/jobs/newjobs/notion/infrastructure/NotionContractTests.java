package com.jobs.newjobs.notion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.notion.application.NotionGateway.IdentityResult;
import com.jobs.newjobs.notion.application.NotionGateway.SchemaResult;
import com.jobs.newjobs.notion.application.NotionSchemaValidator;
import com.jobs.newjobs.notion.application.NotionSchemaValidator.Mutability;
import com.jobs.newjobs.notion.application.NotionSchemaValidator.Owner;
import com.jobs.newjobs.notion.application.NotionSchemaValidator.ValidationResult;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.notion.domain.NotionPropertyType;
import com.jobs.newjobs.notion.domain.NotionSchema;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class NotionContractTests {
	private HttpServer server;
	private URI baseUrl;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.start();
		baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	@AfterEach
	void stopServer() {
		if (server != null) server.stop(0);
	}

	@Test
	void pinsVersionKeepsOpaqueResourceIdsAndPassesCursorThroughUnchanged() {
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<String> firstHeader = new AtomicReference<>();
		List<String> paths = new CopyOnWriteArrayList<>();
		List<String> bodies = new CopyOnWriteArrayList<>();
		server.createContext("/v1/data_sources", exchange -> {
			firstHeader.compareAndSet(null, exchange.getRequestHeaders().getFirst("Notion-Version"));
			paths.add(exchange.getRequestURI().getRawPath());
			bodies.add(read(exchange));
			if (calls.getAndIncrement() == 0) {
				respond(exchange, 200, page("page-active", false, false, true, "opaque/+== cursor value"));
			}
			else {
				respond(exchange, 200, page("page-archived", true, false, false, null));
			}
		});

		IdentityResult result = gateway("data/source ?#id").queryByIdentity(JobSource.HIRING_CAFE, "provider-42", null);

		assertThat(result).isInstanceOf(IdentityResult.Conflict.class);
		assertThat(((IdentityResult.Conflict) result).pages())
			.extracting(page -> page.archived())
			.containsExactly(false, true);
		assertThat(firstHeader).hasValue("2026-03-11");
		assertThat(paths).allMatch(path -> path.contains("data%2Fsource%20%3F%23id"));
		assertThat(bodies.get(1)).contains("\"start_cursor\":\"opaque/+== cursor value\"");
	}

	@Test
	void reportsIncompleteInsteadOfTreatingPartialVisibilityAsNoMatch() {
		server.createContext("/v1/data_sources", exchange -> respond(exchange, 200, """
			{"object":"list","results":[],"has_more":false,
			 "request_status":{"incomplete_reason":"result_limit_reached"}}
			"""));

		IdentityResult result = gateway("source-id").queryByIdentity(JobSource.HIRING_CAFE, "id", null);

		assertThat(result).isInstanceOf(IdentityResult.Incomplete.class);
		assertThat(((IdentityResult.Incomplete) result).reason()).isEqualTo("result_limit_reached");
	}

	@Test
	void mapsSchemaPropertyTypesAndValidatorRequiresNewWhileAllowingExtraProperties() {
		server.createContext("/v1/data_sources", exchange -> respond(exchange, 200, schemaResponse()));

		SchemaResult result = gateway("source-id").retrieveSchema();

		assertThat(result).isInstanceOf(SchemaResult.Available.class);
		NotionSchema schema = ((SchemaResult.Available) result).schema();
		assertThat(schema.properties().get("Job Title").type()).isEqualTo(NotionPropertyType.TITLE);
		assertThat(schema.properties()).containsKey("My Extra Property");
		assertThat(new NotionSchemaValidator().validate(schema)).isInstanceOf(ValidationResult.Valid.class);

		Map<String, NotionSchema.PropertyDefinition> withoutNew = new java.util.LinkedHashMap<>(schema.properties());
		withoutNew.put("Status", new NotionSchema.PropertyDefinition("status-id", "Status",
			NotionPropertyType.STATUS, List.of("Applied")));
		ValidationResult invalid = new NotionSchemaValidator().validate(new NotionSchema("source-id", withoutNew));
		assertThat(invalid).isInstanceOf(ValidationResult.Invalid.class);
		assertThat(((ValidationResult.Invalid) invalid).mismatches())
			.anyMatch(mismatch -> mismatch.reason().contains("New"));
		assertThat(new NotionSchemaValidator().conceptualRules().get("Sync Operation ID").mutability())
			.isEqualTo(Mutability.CREATE_ONLY);
		assertThat(new NotionSchemaValidator().conceptualRules().get("First Synced At").mutability())
			.isEqualTo(Mutability.CREATE_ONLY);
		assertThat(new NotionSchemaValidator().conceptualRules().get("Status").owner()).isEqualTo(Owner.USER);
		assertThat(new NotionSchemaValidator().conceptualRules().get("User Notes").owner()).isEqualTo(Owner.USER);
		assertThat(new NotionSchemaValidator().conceptualRules().get("Applied At").owner()).isEqualTo(Owner.USER);
	}

	@Test
	void retriesRateLimitedReadsWithinTheBoundAndHonorsRetryAfter() {
		AtomicInteger calls = new AtomicInteger();
		server.createContext("/v1/data_sources", exchange -> {
			if (calls.incrementAndGet() == 1) {
				exchange.getResponseHeaders().add("Retry-After", "0");
				respond(exchange, 429, "{\"code\":\"rate_limited\"}");
			}
			else {
				respond(exchange, 200, "{\"results\":[],\"has_more\":false,\"next_cursor\":null}");
			}
		});

		assertThat(gateway("source-id").queryByIdentity(JobSource.HIRING_CAFE, "id", null))
			.isInstanceOf(IdentityResult.None.class);
		assertThat(calls).hasValue(2);
	}

	private HttpNotionGateway gateway(String dataSourceId) {
		NotionProperties properties = new NotionProperties(baseUrl, "2026-03-11", "test-token",
			"database-id", dataSourceId, Duration.ofSeconds(1), Duration.ofSeconds(2));
		return new HttpNotionGateway(HttpClient.newHttpClient(), JsonMapper.builder().build(), properties);
	}

	private static String page(String pageId, boolean archived, boolean inTrash, boolean hasMore, String cursor) {
		return """
			{"object":"list","results":[{"object":"page","id":"%s","archived":%s,"in_trash":%s,
			 "properties":{}}],"has_more":%s,"next_cursor":%s}
			""".formatted(pageId, archived, inTrash, hasMore, cursor == null ? "null" : "\"" + cursor + "\"");
	}

	private static String schemaResponse() {
		return """
			{"id":"source-id","properties":{
			 "Job Title":{"id":"title","type":"title","title":{}},
			 "Source":{"id":"source","type":"select","select":{"options":[]}},
			 "External Job ID":{"id":"external","type":"rich_text","rich_text":{}},
			 "Canonical URL":{"id":"canonical","type":"url","url":{}},
			 "Job URL":{"id":"job-url","type":"url","url":{}},
			 "Company":{"id":"company","type":"rich_text","rich_text":{}},
			 "Status":{"id":"status","type":"status","status":{"options":[{"name":"New"},{"name":"Applied"}]}},
			 "User Notes":{"id":"notes","type":"rich_text","rich_text":{}},
			 "Applied At":{"id":"applied","type":"date","date":{}},
			 "Published At":{"id":"published","type":"date","date":{}},
			 "First Synced At":{"id":"first","type":"date","date":{}},
			 "Sync Operation ID":{"id":"operation","type":"rich_text","rich_text":{}},
			 "Last Synced At":{"id":"last","type":"date","date":{}},
			 "Work Mode":{"id":"work","type":"select","select":{"options":[]}},
			 "Location":{"id":"location","type":"rich_text","rich_text":{}},
			 "Eligibility":{"id":"eligibility","type":"select","select":{"options":[]}},
			 "Seniority":{"id":"seniority","type":"select","select":{"options":[]}},
			 "Technologies":{"id":"tech","type":"multi_select","multi_select":{"options":[]}},
			 "My Extra Property":{"id":"extra","type":"checkbox","checkbox":{}}
			}}
			""";
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
