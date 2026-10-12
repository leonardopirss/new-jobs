package com.jobs.newjobs.filters.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.testcontainers.utility.DockerImageName;

import com.jobs.newjobs.filters.application.SavedFilterRepository;
import com.jobs.newjobs.hiringcafe.application.HiringCafeGateway;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderPage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(SavedFilterApiTests.ProviderStubConfiguration.class)
class SavedFilterApiTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
		.withDatabaseName("job_discovery")
		.withUsername("job_discovery")
		.withPassword("test-only-password");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SavedFilterRepository repository;

	@Autowired
	private JsonMapper jsonMapper;

	@LocalServerPort
	private int port;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@BeforeEach
	void clearFilters() {
		jdbcTemplate.update("delete from saved_filter_configurations");
	}

	@Test
	void createsReadsUpdatesListsAndDeletesSavedFilter() throws Exception {
		HttpResponse<String> created = send("POST", "/api/v1/filters", validFilter("Backend Brazil"));
		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode createdBody = jsonMapper.readTree(created.body());
		String id = createdBody.path("id").asString();
		assertThat(created.headers().firstValue("Location")).contains("/api/v1/filters/" + id);
		assertThat(createdBody.path("version").asLong()).isZero();

		HttpResponse<String> found = send("GET", "/api/v1/filters/" + id, null);
		assertThat(found.statusCode()).isEqualTo(200);
		assertThat(jsonMapper.readTree(found.body()).path("name").asString()).isEqualTo("Backend Brazil");

		HttpResponse<String> updated = send("PUT", "/api/v1/filters/" + id, validFilter("Backend Portugal"));
		assertThat(updated.statusCode()).isEqualTo(200);
		assertThat(jsonMapper.readTree(updated.body()).path("version").asLong()).isEqualTo(1);

		HttpResponse<String> listed = send("GET", "/api/v1/filters", null);
		JsonNode listBody = jsonMapper.readTree(listed.body());
		assertThat(listed.statusCode()).isEqualTo(200);
		assertThat(listBody).hasSize(1);
		assertThat(listBody.get(0).path("name").asString()).isEqualTo("Backend Portugal");

		assertThat(send("DELETE", "/api/v1/filters/" + id, null).statusCode()).isEqualTo(204);
		assertThat(send("GET", "/api/v1/filters/" + id, null).statusCode()).isEqualTo(404);
	}

	@Test
	void rejectsDuplicateNamesIgnoringCase() throws Exception {
		assertThat(send("POST", "/api/v1/filters", validFilter("My Search")).statusCode()).isEqualTo(201);

		HttpResponse<String> conflict = send("POST", "/api/v1/filters", validFilter("my search"));

		assertThat(conflict.statusCode()).isEqualTo(409);
		assertThat(jsonMapper.readTree(conflict.body()).path("code").asString())
			.isEqualTo("FILTER_NAME_CONFLICT");
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void rejectsUnverifiedProviderFilterAndInactiveSource() throws Exception {
		String providerFilter = """
			{
			  "name": "Unverified",
			  "source": "HIRING_CAFE",
			  "specification": {
			    "commonFilters": [],
			    "providerSpecificFilters": [
			      {"name": "remoteOnly", "matchMode": "ANY", "values": ["true"]}
			    ]
			  }
			}
			""";

		HttpResponse<String> unverified = send("POST", "/api/v1/filters", providerFilter);
		assertThat(unverified.statusCode()).isEqualTo(422);
		assertThat(jsonMapper.readTree(unverified.body()).path("code").asString()).isEqualTo("UNSUPPORTED_FILTER");

		String linkedIn = validFilter("LinkedIn future").replace("HIRING_CAFE", "LINKEDIN");
		assertThat(send("POST", "/api/v1/filters", linkedIn).statusCode()).isEqualTo(422);
		assertThat(repository.count()).isZero();
	}

	@Test
	void rejectsSavedFilterWithUndocumentedMultiValueProviderSemantics() throws Exception {
		String ambiguous = validFilter("Ambiguous")
			.replace("[\"Java\"]", "[\"Java\", \"PostgreSQL\"]");

		HttpResponse<String> response = send("POST", "/api/v1/filters", ambiguous);

		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(jsonMapper.readTree(response.body()).path("code").asString()).isEqualTo("UNSUPPORTED_FILTER");
		assertThat(repository.count()).isZero();
	}

	@Test
	void previewReturnsAccuratePartialSummaryAndNeverPersists() throws Exception {
		String preview = """
			{
			  "source": "HIRING_CAFE",
			  "specification": {
			    "commonFilters": [
			      {"field": "TECHNOLOGY", "operation": "INCLUDE", "matchMode": "ANY", "values": ["Java"]}
			    ],
			    "providerSpecificFilters": []
			  }
			}
			""";

		long before = repository.count();
		HttpResponse<String> response = send("POST", "/api/v1/jobs/preview", preview);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode responseBody = jsonMapper.readTree(response.body());
		assertThat(responseBody.path("summary").path("complete").booleanValue()).isFalse();
		assertThat(responseBody.path("summary").path("returned").intValue()).isZero();
		assertThat(responseBody.path("warnings")).isNotEmpty();
		assertThat(repository.count()).isEqualTo(before);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProviderStubConfiguration {
		@Bean
		@Primary
		HiringCafeGateway hiringCafeGatewayStub() {
			return arguments -> new HiringCafeProviderPage(java.util.List.of(), 0, 0L);
		}
	}

	private HttpResponse<String> send(String method, String path, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path));
		if (body == null) {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		}
		else {
			request.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body));
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String validFilter(String name) {
		return """
			{
			  "name": "%s",
			  "source": "HIRING_CAFE",
			  "specification": {
			    "commonFilters": [
			      {"field": "TECHNOLOGY", "operation": "INCLUDE", "matchMode": "ALL", "values": ["Java"]},
			      {"field": "COMPANY", "operation": "EXCLUDE", "matchMode": "ANY", "values": ["Blocked Corp"]}
			    ],
			    "providerSpecificFilters": []
			  }
			}
			""".formatted(name);
	}
}
