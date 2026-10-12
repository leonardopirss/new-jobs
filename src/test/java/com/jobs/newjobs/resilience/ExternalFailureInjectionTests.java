package com.jobs.newjobs.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.notion.application.NotionGateway.IdentityResult;
import com.jobs.newjobs.notion.config.NotionProperties;
import com.jobs.newjobs.notion.domain.NotionFailure.Code;
import com.jobs.newjobs.notion.infrastructure.HttpNotionGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class ExternalFailureInjectionTests {
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

	@ParameterizedTest(name = "HTTP {0} exhausts the bounded retry budget")
	@MethodSource("retryableFailures")
	void exhaustsRetryBudgetWithoutTurningProviderFailureIntoAnEmptyResult(int status, Code expectedCode) {
		AtomicInteger calls = new AtomicInteger();
		server.createContext("/v1/data_sources", exchange -> {
			calls.incrementAndGet();
			exchange.getResponseHeaders().add("Retry-After", "0");
			respond(exchange, status, "{\"code\":\"injected\"}");
		});

		IdentityResult result = gateway().queryByIdentity(JobSource.HIRING_CAFE, "job-1", null);

		assertThat(result).isInstanceOf(IdentityResult.Incomplete.class);
		assertThat(((IdentityResult.Incomplete) result).failure().code()).isEqualTo(expectedCode);
		assertThat(calls).hasValue(3);
	}

	@Test
	void preservesExplicitPaginationIncompleteness() {
		server.createContext("/v1/data_sources", exchange -> respond(exchange, 200, """
			{"results":[],"has_more":false,
			 "request_status":{"incomplete_reason":"result_limit_reached"}}
			"""));

		IdentityResult result = gateway().queryByIdentity(JobSource.HIRING_CAFE, "job-1", null);

		assertThat(result).isInstanceOf(IdentityResult.Incomplete.class);
		assertThat(((IdentityResult.Incomplete) result).reason()).isEqualTo("result_limit_reached");
	}

	@Test
	void malformedProviderItemsFailClosed() {
		server.createContext("/v1/data_sources", exchange -> respond(exchange, 200,
			"{\"results\":[{\"id\":\"broken\"}],\"has_more\":"));

		IdentityResult result = gateway().queryByIdentity(JobSource.HIRING_CAFE, "job-1", null);

		assertThat(result).isInstanceOf(IdentityResult.Incomplete.class);
		assertThat(((IdentityResult.Incomplete) result).failure().code()).isEqualTo(Code.MALFORMED_RESPONSE);
	}

	@Test
	void clientDisconnectExhaustsRetriesAsTransportFailure() {
		AtomicInteger calls = new AtomicInteger();
		server.createContext("/v1/data_sources", exchange -> {
			calls.incrementAndGet();
			exchange.close();
		});

		IdentityResult result = gateway().queryByIdentity(JobSource.HIRING_CAFE, "job-1", null);

		assertThat(result).isInstanceOf(IdentityResult.Incomplete.class);
		assertThat(((IdentityResult.Incomplete) result).failure().code()).isEqualTo(Code.TRANSPORT);
		assertThat(calls).hasValue(3);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("crossSuiteFailureMatrix")
	void everyReleaseFailureBoundaryHasExecutableEvidence(String boundary, String testClass, String testMethod)
		throws ClassNotFoundException {
		Class<?> evidence = Class.forName(testClass);

		assertThat(boundary).isNotBlank();
		assertThat(evidence.getDeclaredMethods()).extracting(method -> method.getName()).contains(testMethod);
	}

	private HttpNotionGateway gateway() {
		NotionProperties properties = new NotionProperties(baseUrl, "2026-03-11", "test-token",
			"database-id", "data-source-id", Duration.ofSeconds(1), Duration.ofSeconds(2));
		return new HttpNotionGateway(HttpClient.newHttpClient(), JsonMapper.builder().build(), properties);
	}

	private static Stream<Arguments> retryableFailures() {
		return Stream.of(
			Arguments.of(429, Code.RATE_LIMITED),
			Arguments.of(503, Code.TRANSIENT_PROVIDER));
	}

	private static Stream<Arguments> crossSuiteFailureMatrix() {
		return Stream.of(
			Arguments.of("lock loss", "com.jobs.newjobs.sync.infrastructure.PostgresDestinationLockTests",
				"terminatedDedicatedConnectionIsDetectedBeforeAnotherWriteCanStart"),
			Arguments.of("marker commit crash boundary",
				"com.jobs.newjobs.sync.application.SyncMarkerFailureInjectionTests",
				"markerCommitFailureStopsBeforePost"),
			Arguments.of("crash before POST", "com.jobs.newjobs.sync.application.SyncMarkerFailureInjectionTests",
				"crashBeforePostLeavesPreparedMarkerForANewRepositoryInstance"),
			Arguments.of("ambiguous create", "com.jobs.newjobs.sync.application.SyncMarkerFailureInjectionTests",
				"connectionLossOrAmbiguousFiveHundredAfterSendStaysUncertainAndIsNotRetried"),
			Arguments.of("client disconnect ownership", "com.jobs.newjobs.imports.infrastructure.ImportIdempotencyTests",
				"restartTakesOverOnlyStaleReservedWorkAndReconcilesStaleProcessing"),
			Arguments.of("malformed imported item", "com.jobs.newjobs.imports.ImportAcceptanceTests",
				"isolatesInvalidItemsWithoutReorderingValidOutcomes"));
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
