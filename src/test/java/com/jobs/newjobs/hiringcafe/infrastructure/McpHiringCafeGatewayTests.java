package com.jobs.newjobs.hiringcafe.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderException;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderPage;
import com.jobs.newjobs.hiringcafe.config.HiringCafeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class McpHiringCafeGatewayTests {
	private HttpServer server;
	private URI endpoint;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.start();
		endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void sendsDocumentedMcpCallAndParsesStructuredContent() {
		AtomicReference<String> requestBody = new AtomicReference<>();
		server.createContext("/mcp", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200, successBody());
		});

		HiringCafeProviderPage page = gateway(1).search(Map.of("posted_within_days", 1, "limit", 25));

		assertThat(page.count()).isEqualTo(1);
		assertThat(page.totalMatches()).isEqualTo(12);
		assertThat(page.jobs()).singleElement().satisfies(job -> {
			assertThat(job.id()).isEqualTo("job-1");
			assertThat(job.technologies()).containsExactly("Java", "PostgreSQL");
			assertThat(job.compensation().minimum()).isEqualByComparingTo("100000");
		});
		assertThat(requestBody.get())
			.contains("\"method\":\"tools/call\"")
			.contains("\"name\":\"search_hiringcafe_jobs\"")
			.contains("\"posted_within_days\":1")
			.contains("\"limit\":25");
	}

	@Test
	void retriesOnlyBoundedRetryableResponses() {
		AtomicInteger calls = new AtomicInteger();
		server.createContext("/mcp", exchange -> {
			if (calls.incrementAndGet() < 3) {
				exchange.getResponseHeaders().add("Retry-After", "0");
				respond(exchange, 503, "unavailable");
			}
			else {
				respond(exchange, 200, successBody());
			}
		});

		assertThat(gateway(3).search(Map.of()).count()).isEqualTo(1);
		assertThat(calls).hasValue(3);
	}

	@Test
	void doesNotRetryNonRetryableClientError() {
		AtomicInteger calls = new AtomicInteger();
		server.createContext("/mcp", exchange -> {
			calls.incrementAndGet();
			respond(exchange, 400, "bad request");
		});

		assertThatThrownBy(() -> gateway(3).search(Map.of()))
			.isInstanceOf(HiringCafeProviderException.class)
			.hasMessageContaining("HTTP 400");
		assertThat(calls).hasValue(1);
	}

	private McpHiringCafeGateway gateway(int maxAttempts) {
		HiringCafeProperties properties = new HiringCafeProperties(
			endpoint, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ZERO, Duration.ZERO,
			maxAttempts, 25
		);
		return new McpHiringCafeGateway(
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
			JsonMapper.builder().build(),
			properties
		);
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	private static String successBody() {
		return """
			{
			  "jsonrpc": "2.0",
			  "id": 1,
			  "result": {
			    "content": [{"type": "text", "text": "one result"}],
			    "structuredContent": {
			      "jobs": [{
			        "id": "job-1",
			        "title": "Backend Engineer",
			        "company": "Example",
			        "location": "Remote",
			        "workplaceType": "Remote",
			        "employmentTypes": ["Full Time"],
			        "compensation": {"currency": "USD", "frequency": "Yearly", "minimum": 100000, "maximum": 140000},
			        "datePosted": "2026-10-11T10:00:00.000Z",
			        "requirementsSummary": "Java services",
			        "seniorityLevel": "Senior Level",
			        "minimumYearsExperience": 5,
			        "responsibilities": ["Build services"],
			        "technologies": ["Java", "PostgreSQL"],
			        "applyUrl": "https://hiringcafe.com/job/job-1"
			      }],
			      "count": 1,
			      "total_matches": 12
			    }
			  }
			}
			""";
	}
}
