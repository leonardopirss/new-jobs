package com.jobs.newjobs.imports.infrastructure;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.jobs.newjobs.shared.web.RequestIdFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class BoundedImportBodyFilter extends OncePerRequestFilter {
	public static final int MAX_BODY_BYTES = 512 * 1024;
	private static final String IMPORT_PATH = "/api/v1/jobs/import";

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equalsIgnoreCase(request.getMethod()) || !IMPORT_PATH.equals(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		if (hasOversizedDeclaredLength(request)) {
			writePayloadTooLarge(request, response);
			return;
		}

		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			writePayloadTooLarge(request, response);
			return;
		}
		filterChain.doFilter(new CachedBodyRequest(request, body), response);
	}

	private static boolean hasOversizedDeclaredLength(HttpServletRequest request) {
		if (request.getContentLengthLong() > MAX_BODY_BYTES) return true;
		return Collections.list(request.getHeaders(HttpHeaders.CONTENT_LENGTH)).stream()
			.flatMap(value -> java.util.Arrays.stream(value.split(",")))
			.map(String::trim)
			.anyMatch(value -> {
				try { return Long.parseLong(value) > MAX_BODY_BYTES; }
				catch (NumberFormatException exception) { return true; }
			});
	}

	private static void writePayloadTooLarge(HttpServletRequest request, HttpServletResponse response)
		throws IOException {
		response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("""
			{"type":"urn:problem:payload-too-large","title":"Payload is too large","status":413,"code":"PAYLOAD_TOO_LARGE","requestId":"%s"}
			""".formatted(RequestIdFilter.from(request)).strip());
	}

	private static final class CachedBodyRequest extends HttpServletRequestWrapper {
		private final byte[] body;

		private CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public int getContentLength() { return body.length; }

		@Override
		public long getContentLengthLong() { return body.length; }

		@Override
		public String getHeader(String name) {
			return HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name) ? Integer.toString(body.length) : super.getHeader(name);
		}

		@Override
		public ServletInputStream getInputStream() {
			return new ByteArrayServletInputStream(body);
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}
	}

	private static final class ByteArrayServletInputStream extends ServletInputStream {
		private final ByteArrayInputStream input;

		private ByteArrayServletInputStream(byte[] body) { this.input = new ByteArrayInputStream(body); }

		@Override public int read() { return input.read(); }
		@Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
		@Override public boolean isFinished() { return input.available() == 0; }
		@Override public boolean isReady() { return true; }
		@Override public void setReadListener(ReadListener readListener) {
			if (readListener == null) throw new IllegalArgumentException("read listener is required");
		}
	}
}
