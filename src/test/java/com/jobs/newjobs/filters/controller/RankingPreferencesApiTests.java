package com.jobs.newjobs.filters.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jobs.newjobs.filters.application.SavedFilterRepository;
import com.jobs.newjobs.hiringcafe.application.HiringCafeGateway;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderJob;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderPage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(RankingPreferencesApiTests.ProviderStubConfiguration.class)
class RankingPreferencesApiTests {
	private static final String MANUAL_API_KEY = "ranking-preferences-api-test-key-0000001";

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
		registry.add("app.security.manual.current-key", () -> MANUAL_API_KEY);
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
	void omittedPreferencesPersistAndReturnTheCompatibilityProfile() throws Exception {
		HttpResponse<String> created = send("POST", "/api/v1/filters", savedFilter("Compatibility", false));

		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode body = jsonMapper.readTree(created.body());
		assertCompatibilityProfile(body.path("rankingPreferences"));
		UUID id = UUID.fromString(body.path("id").asString());
		assertThat(repository.findById(id).orElseThrow().rankingPreferences())
			.isEqualTo(com.jobs.newjobs.ranking.domain.RankingPreferences.compatibilityProfile());
		assertThat(jdbcTemplate.queryForObject(
			"select jsonb_typeof(ranking_preferences) from saved_filter_configurations where id = ?",
			String.class,
			id
		)).isEqualTo("object");
	}

	@Test
	void migrationBackfillsLegacyRowsAndRejectsNonObjectPreferences() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
			insert into saved_filter_configurations
				(id, name, source, filter_specification, version, created_at, updated_at)
			values (?, ?, 'HIRING_CAFE', '{"commonFilters":[],"providerSpecificFilters":[]}'::jsonb,
			        0, now(), now())
			""", id, "Legacy row");

		assertThat(repository.findById(id).orElseThrow().rankingPreferences())
			.isEqualTo(com.jobs.newjobs.ranking.domain.RankingPreferences.compatibilityProfile());
		assertThatThrownBy(() -> jdbcTemplate.update(
			"update saved_filter_configurations set ranking_preferences = '[]'::jsonb where id = ?", id
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void acceptsAdditivePreferencesAndReturnsEffectiveRankedPreview() throws Exception {
		HttpResponse<String> created = send("POST", "/api/v1/filters", savedFilter("Ranked", true));
		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode saved = jsonMapper.readTree(created.body());
		assertThat(saved.path("rankingPreferences").path("searchMode").asString())
			.isEqualTo("REMOTE_FROM_BRAZIL");
		assertThat(saved.path("rankingPreferences").path("requiredTechnologies").get(0).asString())
			.isEqualTo("java");

		HttpResponse<String> preview = send("POST", "/api/v1/jobs/preview", previewRequest("HIRING_CAFE"));
		assertThat(preview.statusCode()).isEqualTo(200);
		JsonNode response = jsonMapper.readTree(preview.body());
		assertThat(response.path("rankingPreferences").path("searchMode").asString())
			.isEqualTo("REMOTE_FROM_BRAZIL");
		assertThat(response.path("summary").path("discovered").asInt()).isEqualTo(3);
		assertThat(response.path("summary").path("returned").asInt()).isEqualTo(1);
		assertThat(response.path("summary").path("excludedIneligible").asInt()).isEqualTo(1);
		assertThat(response.path("jobs").get(0).path("eligibility").path("status").asString())
			.isEqualTo("ELIGIBLE");
		assertThat(response.path("jobs").get(0).path("technologyEvidence")).isNotEmpty();
		assertThat(response.path("jobs").get(0).path("explanations")).isNotEmpty();
	}

	@Test
	void rejectsLinkedInBeforeSavedOrTransientDiscoveryDispatch() throws Exception {
		HttpResponse<String> saved = send(
			"POST", "/api/v1/filters", savedFilter("LinkedIn", false).replace("HIRING_CAFE", "LINKEDIN")
		);
		HttpResponse<String> preview = send("POST", "/api/v1/jobs/preview", previewRequest("LINKEDIN"));

		assertThat(saved.statusCode()).isEqualTo(422);
		assertThat(preview.statusCode()).isEqualTo(422);
		assertThat(repository.count()).isZero();
	}

	private HttpResponse<String> send(String method, String path, String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + path))
			.header("Authorization", "Bearer " + MANUAL_API_KEY)
			.header("Content-Type", "application/json")
			.method(method, HttpRequest.BodyPublishers.ofString(body))
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String savedFilter(String name, boolean withPreferences) {
		String preferences = withPreferences ? """
			,
			  "rankingPreferences": {
			    "searchMode": "REMOTE_FROM_BRAZIL",
			    "requiredTechnologies": ["Java"],
			    "desiredTechnologies": [],
			    "acceptedSeniorities": ["Senior"]
			  }
			""" : "";
		return """
			{
			  "name": "%s",
			  "source": "HIRING_CAFE",
			  "specification": {"commonFilters": [], "providerSpecificFilters": []}
			  %s
			}
			""".formatted(name, preferences);
	}

	private static String previewRequest(String source) {
		return """
			{
			  "source": "%s",
			  "specification": {"commonFilters": [], "providerSpecificFilters": []},
			  "rankingPreferences": {
			    "searchMode": "REMOTE_FROM_BRAZIL",
			    "requiredTechnologies": ["Java"],
			    "desiredTechnologies": [],
			    "acceptedSeniorities": ["Senior"]
			  }
			}
			""".formatted(source);
	}

	private static void assertCompatibilityProfile(JsonNode node) {
		assertThat(node.path("searchMode").asString()).isEqualTo("BOTH");
		assertThat(node.path("requiredTechnologies")).isEmpty();
		assertThat(node.path("desiredTechnologies")).isEmpty();
		assertThat(node.path("acceptedSeniorities")).isEmpty();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProviderStubConfiguration {
		@Bean
		@Primary
		HiringCafeGateway hiringCafeGatewayStub() {
			return arguments -> {
				Instant publishedAt = Instant.now().minusSeconds(60);
				HiringCafeProviderJob eligible = job("ranked-1", "Brazil", publishedAt);
				HiringCafeProviderJob duplicate = job("ranked-1", "Brazil", publishedAt);
				HiringCafeProviderJob ineligible = job("ranked-2", "United States", "Onsite", publishedAt);
				return new HiringCafeProviderPage(List.of(eligible, duplicate, ineligible), 3, 3L);
			};
		}

		private static HiringCafeProviderJob job(String id, String location, Instant publishedAt) {
			return job(id, location, "Remote", publishedAt);
		}

		private static HiringCafeProviderJob job(
			String id, String location, String workplaceType, Instant publishedAt
		) {
			return new HiringCafeProviderJob(
				id, "Senior Java Engineer", "Example", location, workplaceType, List.of("Full Time"),
				null, publishedAt.toString(), "Java services", "Senior", null,
				List.of("Build Java services"), List.of("Java"), "https://example.test/jobs/" + id
			);
		}
	}
}
