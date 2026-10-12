package com.jobs.newjobs.notion.infrastructure;

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
import java.time.Duration;
import java.time.Instant;
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
import com.jobs.newjobs.notion.application.NotionGateway.CreateResult;
import com.jobs.newjobs.notion.application.NotionGateway.IdentityResult;
import com.jobs.newjobs.notion.application.NotionPageMapper;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.notion.domain.NotionPageDraft;
import com.jobs.newjobs.notion.domain.NotionPagePatch;
import com.jobs.newjobs.notion.domain.NotionPropertyValue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class NotionWriteContractTests {
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
	void createSetsNewAndImmutableMarkersWhileUpdatePreservesUserPropertiesAndBlankValues() {
		NotionPageMapper mapper = new NotionPageMapper(new JobIdentityCanonicalizer());
		UUID operationId = UUID.randomUUID();
		Instant now = Instant.parse("2026-10-11T12:00:00Z");

		NotionPageDraft draft = mapper.createDraft(job("  "), operationId, now);
		NotionPagePatch patch = mapper.updatePatch(job("  "), now.plusSeconds(60));

		assertThat(((NotionPropertyValue.Option) draft.properties().get(STATUS)).value()).isEqualTo("New");
		assertThat(((NotionPropertyValue.Text) draft.properties().get(SYNC_OPERATION_ID)).value())
			.isEqualTo(operationId.toString());
		assertThat(draft.properties()).containsKey(FIRST_SYNCED_AT);
		assertThat(patch.properties()).doesNotContainKeys(STATUS, USER_NOTES, APPLIED_AT,
			FIRST_SYNCED_AT, SYNC_OPERATION_ID, "Company");
		assertThat(mapper.updateAllowlist()).doesNotContain(STATUS, USER_NOTES, APPLIED_AT,
			FIRST_SYNCED_AT, SYNC_OPERATION_ID);
	}

	@Test
	void dualIdentityDisagreementIsAConflictAndIncludesArchivedMatches() {
		server.createContext("/v1/data_sources", exchange -> {
			String request = read(exchange);
			boolean external = request.contains("External Job ID");
			respond(exchange, 200, queryPage(external ? "page-by-id" : "page-by-url", !external));
		});

		IdentityResult result = gateway().queryByIdentity(JobSource.HIRING_CAFE, "job-7",
			URI.create("https://example.com/jobs/7"));

		assertThat(result).isInstanceOf(IdentityResult.Conflict.class);
		assertThat(((IdentityResult.Conflict) result).pages())
			.extracting(page -> page.pageId())
			.containsExactly("page-by-id", "page-by-url");
		assertThat(((IdentityResult.Conflict) result).pages().get(1).archived()).isTrue();
	}

	@Test
	void doesNotBlindlyRetryAmbiguousCreate() {
		AtomicInteger requests = new AtomicInteger();
		server.createContext("/v1/pages", exchange -> {
			requests.incrementAndGet();
			respond(exchange, 503, "{\"code\":\"service_unavailable\"}");
		});
		UUID operationId = UUID.randomUUID();
		NotionPageDraft draft = new NotionPageMapper(new JobIdentityCanonicalizer())
			.createDraft(job("Example"), operationId, Instant.parse("2026-10-11T12:00:00Z"));

		CreateResult result = gateway().createPage(draft, operationId);

		assertThat(result).isInstanceOf(CreateResult.Ambiguous.class);
		assertThat(requests).hasValue(1);
	}

	@Test
	void serializedPatchContainsOnlyMappedPropertiesAndNoArchiveControls() {
		AtomicReference<String> body = new AtomicReference<>();
		server.createContext("/v1/pages", exchange -> {
			body.set(read(exchange));
			respond(exchange, 200, queryPageObject("page-1", false));
		});
		NotionPagePatch patch = new NotionPageMapper(new JobIdentityCanonicalizer())
			.updatePatch(job("Example"), Instant.parse("2026-10-11T12:00:00Z"));

		gateway().updatePage("opaque/page-id", patch);

		assertThat(body.get())
			.doesNotContain("Status", "User Notes", "Applied At", "First Synced At", "Sync Operation ID",
				"is_archived", "in_trash");
	}

	private HttpNotionGateway gateway() {
		NotionProperties properties = new NotionProperties(baseUrl, "2026-03-11", "token",
			"database-id", "source-id", Duration.ofSeconds(1), Duration.ofSeconds(2));
		return new HttpNotionGateway(HttpClient.newHttpClient(), JsonMapper.builder().build(), properties);
	}

	private static NormalizedJob job(String company) {
		return new NormalizedJob(JobSource.HIRING_CAFE, "job-7", "Backend Engineer", company,
			"Brazil", "Remote", List.of("Full Time"), null,
			Instant.parse("2026-10-11T10:00:00Z"), "Java and APIs", "Senior", BigDecimal.valueOf(5),
			List.of("Build services"), List.of("Java", "PostgreSQL"),
			URI.create("https://example.com/jobs/7?utm_source=test"));
	}

	private static String queryPage(String id, boolean archived) {
		return "{\"results\":[" + queryPageObject(id, archived) + "],\"has_more\":false,\"next_cursor\":null}";
	}

	private static String queryPageObject(String id, boolean archived) {
		return "{\"id\":\"" + id + "\",\"archived\":" + archived
			+ ",\"in_trash\":false,\"properties\":{}}";
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
