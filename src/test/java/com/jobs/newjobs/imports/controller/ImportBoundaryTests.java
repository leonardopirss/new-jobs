package com.jobs.newjobs.imports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.validation.Validation;

import com.jobs.newjobs.auth.config.IntegrationCredentialProperties;
import com.jobs.newjobs.auth.config.IntegrationCredentialProperties.IntegrationCredential;
import com.jobs.newjobs.auth.infrastructure.ImportBearerAuthenticationFilter;
import com.jobs.newjobs.auth.infrastructure.ImportBearerAuthenticationFilter.IntegrationPrincipal;
import com.jobs.newjobs.imports.infrastructure.BoundedImportBodyFilter;
import com.jobs.newjobs.jobs.domain.JobSource;

import tools.jackson.databind.ObjectMapper;

class ImportBoundaryTests {
	private static final String CURRENT_KEY = "linkedin-current-import-key-00000000000001";
	private static final String PREVIOUS_KEY = "linkedin-previous-import-key-0000000000001";
	private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

	@Test
	void authenticatesSeparateSourceBoundCredentialWithImportScope() throws Exception {
		ImportBearerAuthenticationFilter filter = filter(credential(true, Set.of("jobs:import"), NOW.plusSeconds(60)));
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<Authentication> observed = new AtomicReference<>();

		filter.doFilter(request(CURRENT_KEY), response, (request, ignored) ->
			observed.set(SecurityContextHolder.getContext().getAuthentication()));

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(observed.get()).isNotNull();
		assertThat(observed.get().getAuthorities()).extracting("authority").containsExactly("jobs:import");
		assertThat(observed.get().getPrincipal()).isEqualTo(
			new IntegrationPrincipal("linkedin-collector", JobSource.LINKEDIN));
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void rejectsManualDisabledRevokedAndMissingScopeCredentialsWithoutEchoingSecrets() throws Exception {
		for (Attempt attempt : List.of(
			new Attempt("manual-current-test-key-0000000000000001", credential(true, Set.of("jobs:import"), null), 401),
			new Attempt(CURRENT_KEY, credential(false, Set.of("jobs:import"), null), 401),
			new Attempt(CURRENT_KEY, credential(true, Set.of("jobs:read"), null), 403)
		)) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			filter(attempt.credential()).doFilter(request(attempt.presented()), response, new MockFilterChain());
			assertThat(response.getStatus()).isEqualTo(attempt.status());
			assertThat(response.getContentAsString()).doesNotContain(attempt.presented());
		}
	}

	@Test
	void authenticatesBeforeReadingTheBoundedBody() throws Exception {
		MockHttpServletRequest missingCredential = importRequest(
			new byte[BoundedImportBodyFilter.MAX_BODY_BYTES + 1]);
		MockHttpServletResponse response = new MockHttpServletResponse();
		ImportBearerAuthenticationFilter authentication =
			filter(credential(true, Set.of("jobs:import"), null));
		BoundedImportBodyFilter bodyLimit = new BoundedImportBodyFilter();

		authentication.doFilter(missingCredential, response,
			(request, nestedResponse) -> bodyLimit.doFilter(request, nestedResponse, new MockFilterChain()));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
	}

	@Test
	void acceptsPreviousCredentialOnlyInsideItsBoundedOverlap() throws Exception {
		IntegrationCredential active = credential(true, Set.of("jobs:import"), NOW.plusSeconds(1));
		MockHttpServletResponse beforeExpiry = new MockHttpServletResponse();
		filter(active).doFilter(request(PREVIOUS_KEY), beforeExpiry, new MockFilterChain());
		assertThat(beforeExpiry.getStatus()).isEqualTo(200);

		IntegrationCredential expired = credential(true, Set.of("jobs:import"), NOW);
		MockHttpServletResponse atExpiry = new MockHttpServletResponse();
		filter(expired).doFilter(request(PREVIOUS_KEY), atExpiry, new MockFilterChain());
		assertThat(atExpiry.getStatus()).isEqualTo(401);
	}

	@Test
	void rejectsDeclaredAndStreamedBodiesOver512KibBeforeDeserialization() throws Exception {
		BoundedImportBodyFilter filter = new BoundedImportBodyFilter();
		MockHttpServletRequest declared = importRequest("{}".getBytes(StandardCharsets.UTF_8));
		declared.addHeader(HttpHeaders.CONTENT_LENGTH, BoundedImportBodyFilter.MAX_BODY_BYTES + 1);
		MockHttpServletResponse declaredResponse = new MockHttpServletResponse();
		filter.doFilter(declared, declaredResponse, new MockFilterChain());
		assertThat(declaredResponse.getStatus()).isEqualTo(413);

		byte[] oversized = new byte[BoundedImportBodyFilter.MAX_BODY_BYTES + 1];
		MockHttpServletRequest chunked = importRequest(oversized);
		chunked.removeHeader(HttpHeaders.CONTENT_LENGTH);
		chunked.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
		MockHttpServletResponse chunkedResponse = new MockHttpServletResponse();
		filter.doFilter(chunked, chunkedResponse, new MockFilterChain());
		assertThat(chunkedResponse.getStatus()).isEqualTo(413);
	}

	@Test
	void requestRejectsSourceSpoofingAndEnforcesOneToFiftyItems() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		ImportRequest parsed = mapper.readerFor(ImportRequest.class).readValue("""
			{"jobs":[{"title":"Backend Engineer","externalJobId":"42"}]}
			""");
		assertThat(parsed).isInstanceOf(ImportRequest.class);

		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
			mapper.readerFor(ImportRequest.class).readValue("""
				{"source":"HIRING_CAFE","jobs":[{"title":"Backend Engineer","externalJobId":"42"}]}
				"""))
			.hasMessageContaining("source");
		try (var factory = Validation.buildDefaultValidatorFactory()) {
			var validator = factory.getValidator();
			assertThat(validator.validate(new ImportRequest(List.of())))
				.extracting("propertyPath").extracting(Object::toString).contains("jobs");
			List<ImportRequest.ImportedJob> tooMany = IntStream.rangeClosed(1, 51)
				.mapToObj(index -> new ImportRequest.ImportedJob(
					Integer.toString(index), "Job " + index, null, null, null, null, null,
					null, null, null, null, null, null))
				.toList();
			assertThat(validator.validate(new ImportRequest(tooMany)))
				.extracting("propertyPath").extracting(Object::toString).contains("jobs");
		}
	}

	@Test
	void parsesTypedCredentialsFromExternalJsonAndRejectsWeakSecrets() {
		String json = """
			[{"integrationId":"linkedin-collector","source":"LINKEDIN","scopes":["jobs:import"],
			  "currentKey":"linkedin-current-import-key-00000000000001","enabled":true}]
			""";
		IntegrationCredentialProperties properties = new IntegrationCredentialProperties(List.of(), json);
		assertThat(properties.resolvedCredentials()).singleElement().satisfies(credential -> {
			assertThat(credential.integrationId()).isEqualTo("linkedin-collector");
			assertThat(credential.source()).isEqualTo(JobSource.LINKEDIN);
			assertThat(credential.toString()).doesNotContain(CURRENT_KEY);
		});
		assertThat(properties.toString()).doesNotContain(CURRENT_KEY);

		org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(() ->
			new IntegrationCredential("weak", JobSource.LINKEDIN, Set.of("jobs:import"),
				"too-short", null, null, true));
	}

	private static ImportBearerAuthenticationFilter filter(IntegrationCredential credential) {
		return new ImportBearerAuthenticationFilter(
			new IntegrationCredentialProperties(List.of(credential)),
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static IntegrationCredential credential(boolean enabled, Set<String> scopes, Instant previousValidUntil) {
		return new IntegrationCredential(
			"linkedin-collector", JobSource.LINKEDIN, scopes, CURRENT_KEY,
			previousValidUntil == null ? null : PREVIOUS_KEY, previousValidUntil, enabled);
	}

	private static MockHttpServletRequest request(String credential) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/jobs/import");
		request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
		return request;
	}

	private static MockHttpServletRequest importRequest(byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/jobs/import");
		request.setContentType("application/json");
		request.setContent(body);
		return request;
	}

	private record Attempt(String presented, IntegrationCredential credential, int status) { }
}
