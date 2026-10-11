package com.jobs.newjobs.hiringcafe.infrastructure;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.hiringcafe.application.HiringCafeGateway;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderException;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderJob;
import com.jobs.newjobs.hiringcafe.application.HiringCafeProviderPage;
import com.jobs.newjobs.hiringcafe.config.HiringCafeProperties;
import com.jobs.newjobs.jobs.domain.JobCompensation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class McpHiringCafeGateway implements HiringCafeGateway {
	private static final String SEARCH_TOOL = "search_hiringcafe_jobs";
	private static final String JSON_RPC_VERSION = "2.0";

	private final HttpClient httpClient;
	private final JsonMapper jsonMapper;
	private final HiringCafeProperties properties;

	public McpHiringCafeGateway(HttpClient httpClient, JsonMapper jsonMapper, HiringCafeProperties properties) {
		this.httpClient = httpClient;
		this.jsonMapper = jsonMapper;
		this.properties = properties;
	}

	@Override
	public HiringCafeProviderPage search(Map<String, Object> arguments) {
		String requestBody = createRequest(arguments);
		for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
			try {
				HttpResponse<String> response = httpClient.send(request(requestBody), HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					return parseResponse(response.body());
				}
				if (!isRetryable(response.statusCode()) || attempt == properties.maxAttempts()) {
					throw new HiringCafeProviderException("HiringCafe returned HTTP " + response.statusCode());
				}
				pause(retryDelay(response, attempt));
			}
			catch (IOException exception) {
				if (attempt == properties.maxAttempts()) {
					throw new HiringCafeProviderException("HiringCafe request failed after bounded retries", exception);
				}
				pause(backoff(attempt));
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new HiringCafeProviderException("HiringCafe request was interrupted", exception);
			}
		}
		throw new IllegalStateException("unreachable retry state");
	}

	private HttpRequest request(String body) {
		return HttpRequest.newBuilder(properties.mcpUrl())
			.timeout(properties.requestTimeout())
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
	}

	private String createRequest(Map<String, Object> arguments) {
		ObjectNode root = jsonMapper.createObjectNode();
		root.put("jsonrpc", JSON_RPC_VERSION);
		root.put("id", 1);
		root.put("method", "tools/call");
		ObjectNode params = root.putObject("params");
		params.put("name", SEARCH_TOOL);
		params.set("arguments", jsonMapper.valueToTree(arguments));
		try {
			return jsonMapper.writeValueAsString(root);
		}
		catch (JacksonException exception) {
			throw new HiringCafeProviderException("Could not serialize HiringCafe request", exception);
		}
	}

	private HiringCafeProviderPage parseResponse(String body) {
		try {
			JsonNode root = jsonMapper.readTree(body);
			JsonNode isError = root.path("result").path("isError");
			if (!root.path("error").isMissingNode() || (isError.isBoolean() && isError.booleanValue())) {
				throw new HiringCafeProviderException("HiringCafe returned a JSON-RPC error");
			}
			JsonNode content = root.path("result").path("structuredContent");
			if (!content.isObject() || !content.path("jobs").isArray()) {
				throw new HiringCafeProviderException("HiringCafe response omitted structuredContent.jobs");
			}

			List<HiringCafeProviderJob> jobs = new ArrayList<>();
			for (JsonNode job : content.path("jobs")) {
				jobs.add(mapJob(job));
			}
			JsonNode totalMatches = content.path("total_matches");
			Long total = totalMatches.isNumber() ? totalMatches.longValue() : null;
			return new HiringCafeProviderPage(jobs, content.path("count").intValue(), total);
		}
		catch (JacksonException exception) {
			throw new HiringCafeProviderException("Could not parse HiringCafe response", exception);
		}
	}

	private HiringCafeProviderJob mapJob(JsonNode job) {
		return new HiringCafeProviderJob(
			requiredString(job, "id"),
			requiredString(job, "title"),
			requiredString(job, "company"),
			requiredString(job, "location"),
			requiredString(job, "workplaceType"),
			strings(job.path("employmentTypes")),
			compensation(job.path("compensation")),
			nullableString(job.path("datePosted")),
			nullableString(job.path("requirementsSummary")),
			nullableString(job.path("seniorityLevel")),
			decimal(job.path("minimumYearsExperience")),
			strings(job.path("responsibilities")),
			strings(job.path("technologies")),
			requiredString(job, "applyUrl")
		);
	}

	private static JobCompensation compensation(JsonNode node) {
		if (node.isNull() || node.isMissingNode()) {
			return null;
		}
		return new JobCompensation(
			requiredString(node, "currency"),
			requiredString(node, "frequency"),
			decimal(node.path("minimum")),
			decimal(node.path("maximum"))
		);
	}

	private static BigDecimal decimal(JsonNode node) {
		return node.isNumber() ? node.decimalValue() : null;
	}

	private static List<String> strings(JsonNode node) {
		if (!node.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode value : node) {
			if (value.isString()) {
				values.add(value.stringValue());
			}
		}
		return List.copyOf(values);
	}

	private static String requiredString(JsonNode node, String name) {
		String value = nullableString(node.path(name));
		if (value == null || value.isBlank()) {
			throw new HiringCafeProviderException("HiringCafe response has invalid " + name);
		}
		return value;
	}

	private static String nullableString(JsonNode node) {
		return node.isString() ? node.stringValue() : null;
	}

	private static boolean isRetryable(int status) {
		return status == 429 || status == 502 || status == 503 || status == 504;
	}

	private Duration retryDelay(HttpResponse<?> response, int attempt) {
		Duration requested = response.headers().firstValue("Retry-After")
			.flatMap(McpHiringCafeGateway::parseRetryAfter)
			.orElse(backoff(attempt));
		return requested.compareTo(properties.maxRetryDelay()) > 0 ? properties.maxRetryDelay() : requested;
	}

	private Duration backoff(int attempt) {
		long multiplier = 1L << Math.max(0, attempt - 1);
		Duration delay = properties.retryBackoff().multipliedBy(multiplier);
		return delay.compareTo(properties.maxRetryDelay()) > 0 ? properties.maxRetryDelay() : delay;
	}

	private static java.util.Optional<Duration> parseRetryAfter(String value) {
		try {
			return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
		}
		catch (NumberFormatException exception) {
			return java.util.Optional.empty();
		}
	}

	private static void pause(Duration delay) {
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new HiringCafeProviderException("HiringCafe retry wait was interrupted", exception);
		}
	}
}
