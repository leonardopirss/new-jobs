package com.jobs.newjobs.notion.application;

import static com.jobs.newjobs.notion.application.NotionSchemaValidator.CANONICAL_URL;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.COMPANY;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.EXTERNAL_JOB_ID;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.FIRST_SYNCED_AT;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.JOB_TITLE;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.JOB_URL;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.LAST_SYNCED_AT;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.LOCATION;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.NEW_STATUS;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.PUBLISHED_AT;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.SENIORITY;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.SOURCE;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.STATUS;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.SYNC_OPERATION_ID;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.TECHNOLOGIES;
import static com.jobs.newjobs.notion.application.NotionSchemaValidator.WORK_MODE;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer;
import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer.CanonicalIdentity;
import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.notion.domain.NotionPageDraft;
import com.jobs.newjobs.notion.domain.NotionPagePatch;
import com.jobs.newjobs.notion.domain.NotionPropertyType;
import com.jobs.newjobs.notion.domain.NotionPropertyValue;

@Component
public final class NotionPageMapper {
	private static final int RICH_TEXT_LIMIT = 2_000;
	private static final Set<String> UPDATE_ALLOWLIST = Set.of(
		JOB_TITLE, SOURCE, EXTERNAL_JOB_ID, CANONICAL_URL, JOB_URL, COMPANY, PUBLISHED_AT,
		LAST_SYNCED_AT, WORK_MODE, LOCATION, SENIORITY, TECHNOLOGIES
	);

	private final JobIdentityCanonicalizer canonicalizer;

	public NotionPageMapper(JobIdentityCanonicalizer canonicalizer) {
		this.canonicalizer = canonicalizer;
	}

	public NotionPageDraft createDraft(NormalizedJob job, UUID operationId, Instant now) {
		if (operationId == null || now == null) {
			throw new IllegalArgumentException("operation ID and synchronization time are required");
		}
		CanonicalIdentity identity = canonicalizer.canonicalize(job);
		Map<String, NotionPropertyValue> properties = mutableProperties(job, identity, now);
		properties.put(STATUS, new NotionPropertyValue.Option(NotionPropertyType.STATUS, NEW_STATUS));
		properties.put(FIRST_SYNCED_AT, new NotionPropertyValue.Date(now));
		properties.put(SYNC_OPERATION_ID,
			new NotionPropertyValue.Text(NotionPropertyType.RICH_TEXT, operationId.toString()));
		return new NotionPageDraft(properties, descriptionChunks(job));
	}

	public NotionPagePatch updatePatch(NormalizedJob job, Instant now) {
		if (now == null) {
			throw new IllegalArgumentException("synchronization time is required");
		}
		Map<String, NotionPropertyValue> mapped = mutableProperties(job, canonicalizer.canonicalize(job), now);
		mapped.keySet().retainAll(UPDATE_ALLOWLIST);
		return new NotionPagePatch(mapped);
	}

	public Set<String> updateAllowlist() {
		return UPDATE_ALLOWLIST;
	}

	public static boolean isUpdateAllowed(String property) {
		return UPDATE_ALLOWLIST.contains(property);
	}

	private static Map<String, NotionPropertyValue> mutableProperties(
		NormalizedJob job,
		CanonicalIdentity identity,
		Instant now
	) {
		Map<String, NotionPropertyValue> values = new LinkedHashMap<>();
		putText(values, JOB_TITLE, NotionPropertyType.TITLE, job.title());
		putOption(values, SOURCE, job.source().name());
		putText(values, EXTERNAL_JOB_ID, NotionPropertyType.RICH_TEXT, identity.externalJobId());
		putLink(values, CANONICAL_URL, identity.canonicalUrl());
		putLink(values, JOB_URL, job.jobUrl());
		putText(values, COMPANY, NotionPropertyType.RICH_TEXT, job.company());
		putDate(values, PUBLISHED_AT, job.publishedAt());
		putDate(values, LAST_SYNCED_AT, now);
		putText(values, WORK_MODE, NotionPropertyType.RICH_TEXT, job.workplaceType());
		putText(values, LOCATION, NotionPropertyType.RICH_TEXT, job.location());
		putText(values, SENIORITY, NotionPropertyType.RICH_TEXT, job.seniorityLevel());
		List<String> technologies = clean(job.technologies());
		if (!technologies.isEmpty()) {
			values.put(TECHNOLOGIES, new NotionPropertyValue.Options(technologies));
		}
		return values;
	}

	private static List<String> descriptionChunks(NormalizedJob job) {
		List<String> sections = new ArrayList<>();
		addSection(sections, "Requirements", job.requirementsSummary());
		addSection(sections, "Responsibilities", String.join("\n", clean(job.responsibilities())));
		List<String> chunks = new ArrayList<>();
		for (String section : sections) {
			for (int start = 0; start < section.length(); start += RICH_TEXT_LIMIT) {
				chunks.add(section.substring(start, Math.min(start + RICH_TEXT_LIMIT, section.length())));
			}
		}
		return List.copyOf(chunks);
	}

	private static void addSection(List<String> sections, String heading, String content) {
		if (content != null && !content.isBlank()) {
			sections.add(heading + "\n" + content.trim());
		}
	}

	private static List<String> clean(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
	}

	private static void putText(Map<String, NotionPropertyValue> values, String name,
		NotionPropertyType type, String value) {
		if (value != null && !value.isBlank()) {
			values.put(name, new NotionPropertyValue.Text(type, value.trim()));
		}
	}

	private static void putOption(Map<String, NotionPropertyValue> values, String name, String value) {
		if (value != null && !value.isBlank()) {
			values.put(name, new NotionPropertyValue.Option(NotionPropertyType.SELECT, value.trim()));
		}
	}

	private static void putLink(Map<String, NotionPropertyValue> values, String name, URI value) {
		if (value != null) {
			values.put(name, new NotionPropertyValue.Link(value.toString()));
		}
	}

	private static void putDate(Map<String, NotionPropertyValue> values, String name, Instant value) {
		if (value != null) {
			values.put(name, new NotionPropertyValue.Date(value));
		}
	}
}
