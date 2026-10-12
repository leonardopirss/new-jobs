package com.jobs.newjobs.auth.config;

import java.io.IOException;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import com.jobs.newjobs.auth.infrastructure.ManualBearerAuthenticationFilter;
import com.jobs.newjobs.shared.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ManualCredentialProperties.class)
public class ApiSecurityConfiguration {
	@Bean
	SecurityFilterChain apiSecurityFilterChain(
		HttpSecurity http,
		ManualBearerAuthenticationFilter manualBearerAuthenticationFilter
	) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.cors(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.securityContext(context -> context.requireExplicitSave(true))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((request, response, exception) ->
					writeUnauthorized(request, response, "AUTHENTICATION_REQUIRED"))
				.accessDeniedHandler((request, response, exception) -> writeForbidden(request, response)))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers("/api/v1/filters/**", "/api/v1/jobs/preview", "/api/v1/jobs/sync")
				.hasAuthority("MANUAL_API")
				.anyRequest().denyAll())
			.addFilterBefore(manualBearerAuthenticationFilter, AnonymousAuthenticationFilter.class);
		return http.build();
	}

	public static void writeUnauthorized(
		HttpServletRequest request,
		HttpServletResponse response,
		String code
	) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		writeProblem(request, response, HttpServletResponse.SC_UNAUTHORIZED, code, "Authentication is required");
	}

	private static void writeForbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		writeProblem(request, response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Access is denied");
	}

	private static void writeProblem(
		HttpServletRequest request,
		HttpServletResponse response,
		int status,
		String code,
		String title
	)
		throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("""
			{"type":"urn:problem:%s","title":"%s","status":%d,"code":"%s","requestId":"%s"}
			""".formatted(
				code.toLowerCase().replace('_', '-'), title, status, code, RequestIdFilter.from(request)
			).strip());
	}
}
