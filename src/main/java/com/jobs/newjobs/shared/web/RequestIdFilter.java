package com.jobs.newjobs.shared.web;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter extends OncePerRequestFilter {
	public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
	public static final String HEADER = "X-Request-Id";

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		filterChain.doFilter(request, response);
	}

	public static String from(HttpServletRequest request) {
		Object requestId = request.getAttribute(ATTRIBUTE);
		return requestId instanceof String value ? value : "unavailable";
	}
}
