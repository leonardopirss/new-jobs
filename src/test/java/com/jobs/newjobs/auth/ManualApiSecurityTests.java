package com.jobs.newjobs.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.jobs.newjobs.hiringcafe.application.HiringCafeGateway;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderPage;
import com.jobs.newjobs.auth.application.ManualCredentialVerifier;
import com.jobs.newjobs.auth.config.ManualCredentialProperties;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(ManualApiSecurityTests.ProviderSpyConfiguration.class)
class ManualApiSecurityTests {
 private static final String CURRENT_KEY = "manual-current-test-key-0000000000000001";
 private static final String PREVIOUS_KEY = "manual-previous-test-key-00000000000001";

 @Container
 static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
  .withDatabaseName("job_discovery")
  .withUsername("job_discovery")
  .withPassword("test-only-password");

 @DynamicPropertySource
 static void properties(DynamicPropertyRegistry registry) {
  registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
  registry.add("spring.datasource.username", POSTGRES::getUsername);
  registry.add("spring.datasource.password", POSTGRES::getPassword);
  registry.add("app.security.manual.current-key", () -> CURRENT_KEY);
  registry.add("app.security.manual.previous-key", () -> PREVIOUS_KEY);
  registry.add("app.security.manual.previous-valid-until", () -> "2099-01-01T00:00:00Z");
 }

 @LocalServerPort
 private int port;

 @org.springframework.beans.factory.annotation.Autowired
 private JdbcTemplate jdbcTemplate;

 @org.springframework.beans.factory.annotation.Autowired
 private AtomicInteger providerInvocations;

 private final HttpClient httpClient = HttpClient.newBuilder()
  .followRedirects(HttpClient.Redirect.NEVER)
  .build();

 @BeforeEach
 void resetState() {
  jdbcTemplate.update("delete from saved_filter_configurations");
  providerInvocations.set(0);
 }

 @Test
 void protectsManualRoutesAndDoesNotCreateASession() throws Exception {
  HttpResponse<String> missing = send("GET", "/api/v1/filters", null, null);
  assertThat(missing.statusCode()).isEqualTo(401);
		assertThat(missing.headers().firstValue("WWW-Authenticate")).contains("Bearer");
		assertThat(missing.body()).contains("AUTHENTICATION_REQUIRED");
		assertThat(missing.headers().firstValue("X-Request-Id")).isPresent();
		assertThat(missing.body()).contains("requestId");
  assertThat(missing.headers().firstValue("Set-Cookie")).isEmpty();

  HttpResponse<String> invalid = send("GET", "/api/v1/filters", null, "wrong-key");
  assertThat(invalid.statusCode()).isEqualTo(401);
  assertThat(invalid.body()).contains("INVALID_CREDENTIAL");

  assertThat(send("GET", "/api/v1/filters", null, CURRENT_KEY).statusCode()).isEqualTo(200);
  assertThat(send("GET", "/api/v1/filters", null, PREVIOUS_KEY).statusCode()).isEqualTo(200);
 }

 @Test
 void exposesOnlySanitizedHealthAnonymouslyAndDeniesUnknownRoutes() throws Exception {
  HttpResponse<String> health = send("GET", "/actuator/health", null, null);
  assertThat(health.statusCode()).isEqualTo(200);
  assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("components");

  HttpResponse<String> unknown = send("GET", "/api/v1/not-a-route", null, CURRENT_KEY);
  assertThat(unknown.statusCode()).isEqualTo(403);
  assertThat(unknown.body()).contains("ACCESS_DENIED");
 }

	@Test
	void invalidCredentialNeverInvokesTheProvider() throws Exception {
  String body = """
   {
     "source": "HIRING_CAFE",
     "specification": {"commonFilters": [], "providerSpecificFilters": []}
   }
   """;

  HttpResponse<String> response = send("POST", "/api/v1/jobs/preview", body, "wrong-key");

  assertThat(response.statusCode()).isEqualTo(401);
		assertThat(providerInvocations).hasValue(0);
	}

	@Test
	void rejectsPreviousCredentialAtTheExpirationBoundary() {
		Instant expiration = Instant.parse("2026-10-11T12:00:00Z");
		ManualCredentialProperties properties = new ManualCredentialProperties(
			CURRENT_KEY, PREVIOUS_KEY, expiration
		);
		ManualCredentialVerifier verifier = new ManualCredentialVerifier(
			properties, Clock.fixed(expiration, ZoneOffset.UTC)
		);

		assertThat(verifier.isValid(CURRENT_KEY)).isTrue();
		assertThat(verifier.isValid(PREVIOUS_KEY)).isFalse();
	}

 private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
  HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
  if (bearer != null) {
   request.header("Authorization", "Bearer " + bearer);
  }
  if (body == null) {
   request.method(method, HttpRequest.BodyPublishers.noBody());
  }
  else {
   request.header("Content-Type", "application/json")
    .method(method, HttpRequest.BodyPublishers.ofString(body));
  }
  return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
 }

 @TestConfiguration(proxyBeanMethods = false)
 static class ProviderSpyConfiguration {
  @Bean
  AtomicInteger providerInvocations() {
   return new AtomicInteger();
  }

  @Bean
  @Primary
  HiringCafeGateway providerSpy(AtomicInteger invocations) {
   return arguments -> {
    invocations.incrementAndGet();
    return new HiringCafeProviderPage(java.util.List.of(), 0, 0L);
   };
  }
 }
}
