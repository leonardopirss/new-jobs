package com.jobs.newjobs.auth.infrastructure;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.jobs.newjobs.auth.application.ManualCredentialVerifier;
import com.jobs.newjobs.auth.config.ApiSecurityConfiguration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class ManualBearerAuthenticationFilter extends OncePerRequestFilter {
	private static final String BEARER_PREFIX = "Bearer ";
	private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(
		new SimpleGrantedAuthority("MANUAL_API")
	);

	private final ManualCredentialVerifier verifier;

	public ManualBearerAuthenticationFilter(ManualCredentialVerifier verifier) {
		this.verifier = verifier;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		List<String> authorizationHeaders = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
		if (authorizationHeaders.isEmpty()) {
			filterChain.doFilter(request, response);
			return;
		}
		if (authorizationHeaders.size() != 1) {
			ApiSecurityConfiguration.writeUnauthorized(request, response, "INVALID_CREDENTIAL");
			return;
		}

		String authorization = authorizationHeaders.getFirst();
		if (authorization.length() <= BEARER_PREFIX.length()
			|| !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			ApiSecurityConfiguration.writeUnauthorized(request, response, "INVALID_CREDENTIAL");
			return;
		}

		String credential = authorization.substring(BEARER_PREFIX.length());
		if (!verifier.isValid(credential)) {
			ApiSecurityConfiguration.writeUnauthorized(request, response, "INVALID_CREDENTIAL");
			return;
		}

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new UsernamePasswordAuthenticationToken("manual-api", null, AUTHORITIES));
		SecurityContextHolder.setContext(context);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}
}
