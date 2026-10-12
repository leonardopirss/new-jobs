package com.jobs.newjobs.auth.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Collections;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.jobs.newjobs.auth.config.ApiSecurityConfiguration;
import com.jobs.newjobs.auth.config.IntegrationCredentialProperties;
import com.jobs.newjobs.auth.config.IntegrationCredentialProperties.IntegrationCredential;
import com.jobs.newjobs.jobs.domain.JobSource;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class ImportBearerAuthenticationFilter extends OncePerRequestFilter {
	private static final String IMPORT_PATH = "/api/v1/jobs/import";
	private static final String IMPORT_SCOPE = "jobs:import";
	private static final String BEARER_PREFIX = "Bearer ";
	private static final byte[] NO_PREVIOUS_CREDENTIAL = new byte[32];

	private final List<PreparedCredential> credentials;
	private final Clock clock;

	public ImportBearerAuthenticationFilter(IntegrationCredentialProperties properties, Clock clock) {
		this.credentials = properties.resolvedCredentials().stream().map(PreparedCredential::new).toList();
		this.clock = clock;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !IMPORT_PATH.equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		List<String> headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
		if (headers.size() != 1) {
			ApiSecurityConfiguration.writeUnauthorized(request, response,
				headers.isEmpty() ? "AUTHENTICATION_REQUIRED" : "INVALID_CREDENTIAL");
			return;
		}
		String authorization = headers.getFirst();
		if (authorization.length() <= BEARER_PREFIX.length()
			|| !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			ApiSecurityConfiguration.writeUnauthorized(request, response, "INVALID_CREDENTIAL");
			return;
		}

		PreparedCredential matched = match(authorization.substring(BEARER_PREFIX.length()));
		if (matched == null) {
			ApiSecurityConfiguration.writeUnauthorized(request, response, "INVALID_CREDENTIAL");
			return;
		}
		if (!matched.configuration().scopes().contains(IMPORT_SCOPE)) {
			ApiSecurityConfiguration.writeForbidden(request, response);
			return;
		}

		IntegrationCredential configured = matched.configuration();
		IntegrationPrincipal principal = new IntegrationPrincipal(configured.integrationId(), configured.source());
		List<SimpleGrantedAuthority> authorities = configured.scopes().stream()
			.sorted().map(SimpleGrantedAuthority::new).toList();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
		SecurityContextHolder.setContext(context);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	private PreparedCredential match(String presented) {
		byte[] presentedDigest = digest(presented == null ? "" : presented);
		PreparedCredential matched = null;
		for (PreparedCredential candidate : credentials) {
			boolean currentMatches = MessageDigest.isEqual(candidate.currentDigest(), presentedDigest);
			boolean previousMatches = MessageDigest.isEqual(candidate.previousDigest(), presentedDigest);
			boolean previousActive = candidate.configuration().previousValidUntil() != null
				&& clock.instant().isBefore(candidate.configuration().previousValidUntil());
			if (candidate.configuration().enabled() && (currentMatches || (previousActive && previousMatches))) {
				matched = candidate;
			}
		}
		return matched;
	}

	private static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
		}
	}

	public record IntegrationPrincipal(String integrationId, JobSource source) { }

	private record PreparedCredential(
		IntegrationCredential configuration,
		byte[] currentDigest,
		byte[] previousDigest
	) {
		private PreparedCredential(IntegrationCredential configuration) {
			this(configuration, digest(configuration.currentKey()), configuration.previousKey() == null
				? NO_PREVIOUS_CREDENTIAL : digest(configuration.previousKey()));
		}
	}
}
