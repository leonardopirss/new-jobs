package com.jobs.newjobs.sync.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jobs.newjobs.auth.application.ManualCredentialVerifier;
import com.jobs.newjobs.auth.config.ApiSecurityConfiguration;
import com.jobs.newjobs.auth.infrastructure.ManualBearerAuthenticationFilter;
import com.jobs.newjobs.sync.application.ManualSyncService;
import com.jobs.newjobs.sync.application.SyncPreflightUnavailableException;
import com.jobs.newjobs.sync.domain.SyncItemOutcome;
import com.jobs.newjobs.sync.domain.SyncItemStatus;
import com.jobs.newjobs.sync.domain.SyncRunResult;

@WebMvcTest(
	controllers = ManualSyncController.class,
	properties = "app.security.manual.current-key=manual-sync-api-test-key-000000000001"
)
@Import({
	ApiSecurityConfiguration.class,
	ManualBearerAuthenticationFilter.class,
	ManualCredentialVerifier.class,
	ManualSyncApiTests.ClockConfiguration.class
})
@ImportAutoConfiguration({
	SecurityAutoConfiguration.class,
	ServletWebSecurityAutoConfiguration.class,
	SecurityFilterAutoConfiguration.class
})
class ManualSyncApiTests {
	private static final String API_KEY = "manual-sync-api-test-key-000000000001";

	@MockitoBean
	private ManualSyncService service;

	@Autowired
	private MockMvc mvc;

	@BeforeEach
	void synchronousResult() {
		when(service.synchronize(any())).thenAnswer(invocation -> {
			ManualSyncRequest request = invocation.getArgument(0);
			request.validateMode();
			return result();
		});
	}

	@Test
	void acceptsExactlyOneSavedFilterModeAndReturnsExactSynchronousAccounting() throws Exception {
		mvc.perform(authenticated("""
			{"savedFilterId":"4b15c597-d47e-4f36-a4b8-71d42a634dec"}
			"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PARTIAL"))
			.andExpect(jsonPath("$.summary.received").value(2))
			.andExpect(jsonPath("$.summary.updated").value(1))
			.andExpect(jsonPath("$.summary.notProcessed").value(1))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].index").value(0))
			.andExpect(jsonPath("$.items[1].index").value(1));
	}

	@Test
	void acceptsTransientHiringCafeCriteria() throws Exception {
		mvc.perform(authenticated("""
			{
			  "source":"HIRING_CAFE",
			  "specification":{"commonFilters":[],"providerSpecificFilters":[]}
			}
			"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.requestId").isNotEmpty());
	}

	@Test
	void rejectsBothModesAndRejectsNoMode() throws Exception {
		mvc.perform(authenticated("""
			{
			  "savedFilterId":"4b15c597-d47e-4f36-a4b8-71d42a634dec",
			  "source":"HIRING_CAFE",
			  "specification":{"commonFilters":[],"providerSpecificFilters":[]}
			}
			"""))
			.andExpect(status().isUnprocessableContent());

		mvc.perform(authenticated("{}"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void returnsServiceUnavailableOnlyForPreflightFailureBeforeAResult() throws Exception {
		doThrow(new SyncPreflightUnavailableException("DESTINATION_LOCK_BUSY", "Notion destination is busy"))
			.when(service).synchronize(any());

		mvc.perform(authenticated("""
			{"savedFilterId":"4b15c597-d47e-4f36-a4b8-71d42a634dec"}
			"""))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("DESTINATION_LOCK_BUSY"));
	}

	@Test
	void requiresManualAuthentication() throws Exception {
		mvc.perform(post("/api/v1/jobs/sync")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isUnauthorized());
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticated(String body) {
		return post("/api/v1/jobs/sync")
			.header("Authorization", "Bearer " + API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private static SyncRunResult result() {
		return SyncRunResult.from(UUID.fromString("a9a0ab51-ff49-4438-8bb1-94f85e19718f"), List.of(
			new SyncItemOutcome(0, SyncItemStatus.UPDATED, "existing", "page-1", null),
			new SyncItemOutcome(1, SyncItemStatus.NOT_PROCESSED, "new", null, "CREATE_QUOTA_EXHAUSTED")
		));
	}

	static class ClockConfiguration {
		@Bean
		Clock clock() {
			return Clock.systemUTC();
		}
	}
}
