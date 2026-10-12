package com.jobs.newjobs.jobs.application;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.jobs.domain.NormalizedJob;

@Component
public final class JobIdentityCanonicalizer {
	private static final Set<String> TRACKING_PARAMETERS = Set.of(
		"fbclid", "gclid", "mc_cid", "mc_eid", "msclkid"
	);

	public CanonicalIdentity canonicalize(NormalizedJob job) {
		Objects.requireNonNull(job, "job is required");
		String externalId = normalizeExternalId(job.externalJobId());
		URI canonicalUrl = job.jobUrl() == null ? null : canonicalizeUrl(job.jobUrl());
		return new CanonicalIdentity(job.source(), externalId, canonicalUrl);
	}

	public String stableKey(NormalizedJob job) {
		return canonicalize(job).stableKey();
	}

	public URI canonicalizeUrl(URI url) {
		Objects.requireNonNull(url, "url is required");
		String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new IllegalArgumentException("job URL must use http or https");
		}
		if (url.getUserInfo() != null) {
			throw new IllegalArgumentException("job URL must not contain user information");
		}
		if (url.getHost() == null || url.getHost().isBlank()) {
			throw new IllegalArgumentException("job URL must contain a valid host");
		}

		String host = url.getHost().toLowerCase(Locale.ROOT);
		int port = isDefaultPort(scheme, url.getPort()) ? -1 : url.getPort();
		String path = normalizePath(url.getRawPath());
		String query = stripTrackingParameters(url.getRawQuery());
		String authority = host + (port < 0 ? "" : ":" + port);
		return URI.create(scheme + "://" + authority + path + (query == null ? "" : "?" + query));
	}

	private static String normalizeExternalId(String externalId) {
		if (externalId == null || externalId.isBlank()) {
			return null;
		}
		return externalId.trim();
	}

	private static boolean isDefaultPort(String scheme, int port) {
		return (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
	}

	private static String normalizePath(String rawPath) {
		if (rawPath == null || rawPath.isEmpty()) {
			return "/";
		}
		return URI.create(rawPath).normalize().getRawPath();
	}

	private static String stripTrackingParameters(String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return null;
		}
		String retained = Arrays.stream(rawQuery.split("&", -1))
			.filter(parameter -> !isTrackingParameter(parameter))
			.collect(Collectors.joining("&"));
		return retained.isEmpty() ? null : retained;
	}

	private static boolean isTrackingParameter(String rawParameter) {
		String rawName = rawParameter.split("=", 2)[0];
		String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
		return name.startsWith("utm_") || TRACKING_PARAMETERS.contains(name);
	}

	public record CanonicalIdentity(JobSource source, String externalJobId, URI canonicalUrl) {
		public CanonicalIdentity {
			Objects.requireNonNull(source, "source is required");
			if ((externalJobId == null || externalJobId.isBlank()) && canonicalUrl == null) {
				throw new IllegalArgumentException("external job ID or canonical URL is required");
			}
		}

		public String stableKey() {
			return externalJobId != null
				? source.name() + ":id:" + externalJobId
				: source.name() + ":url:" + canonicalUrl;
		}

		public IdentityRelationship relationshipTo(CanonicalIdentity other) {
			Objects.requireNonNull(other, "other identity is required");
			if (source != other.source) {
				return IdentityRelationship.DISTINCT;
			}

			boolean sameId = externalJobId != null && externalJobId.equals(other.externalJobId);
			boolean sameUrl = canonicalUrl != null && canonicalUrl.equals(other.canonicalUrl);
			if (sameId) {
				return canonicalUrl != null && other.canonicalUrl != null && !sameUrl
					? IdentityRelationship.CONFLICT
					: IdentityRelationship.MATCH;
			}
			if (sameUrl) {
				return externalJobId != null && other.externalJobId != null
					? IdentityRelationship.CONFLICT
					: IdentityRelationship.MATCH;
			}
			return IdentityRelationship.DISTINCT;
		}
	}

	public enum IdentityRelationship {
		MATCH,
		DISTINCT,
		CONFLICT
	}
}
