package com.jobs.newjobs.notion.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.notion.domain.NotionPropertyType;
import com.jobs.newjobs.notion.domain.NotionSchema;

@Component
public final class NotionSchemaValidator {
	public static final String JOB_TITLE = "Job Title";
	public static final String SOURCE = "Source";
	public static final String EXTERNAL_JOB_ID = "External Job ID";
	public static final String CANONICAL_URL = "Canonical URL";
	public static final String JOB_URL = "Job URL";
	public static final String COMPANY = "Company";
	public static final String STATUS = "Status";
	public static final String USER_NOTES = "User Notes";
	public static final String APPLIED_AT = "Applied At";
	public static final String PUBLISHED_AT = "Published At";
	public static final String FIRST_SYNCED_AT = "First Synced At";
	public static final String SYNC_OPERATION_ID = "Sync Operation ID";
	public static final String LAST_SYNCED_AT = "Last Synced At";
	public static final String WORK_MODE = "Work Mode";
	public static final String LOCATION = "Location";
	public static final String ELIGIBILITY = "Eligibility";
	public static final String SENIORITY = "Seniority";
	public static final String TECHNOLOGIES = "Technologies";
	public static final String NEW_STATUS = "New";

	private static final Map<String, PropertyRule> RULES = rules();

	public ValidationResult validate(NotionSchema schema) {
		List<SchemaMismatch> mismatches = new ArrayList<>();
		for (PropertyRule rule : RULES.values()) {
			NotionSchema.PropertyDefinition actual = schema.properties().get(rule.name());
			if (actual == null) {
				mismatches.add(new SchemaMismatch(rule.name(), rule.types(), null, "missing required property"));
				continue;
			}
			if (!rule.types().contains(actual.type())) {
				mismatches.add(new SchemaMismatch(rule.name(), rule.types(), actual.type(), "incompatible property type"));
			}
		}

		NotionSchema.PropertyDefinition status = schema.properties().get(STATUS);
		if (status != null && RULES.get(STATUS).types().contains(status.type())
			&& status.options().stream().noneMatch(NEW_STATUS::equals)) {
			mismatches.add(new SchemaMismatch(STATUS, RULES.get(STATUS).types(), status.type(),
				"required status option New is missing"));
		}

		return mismatches.isEmpty() ? new ValidationResult.Valid() : new ValidationResult.Invalid(mismatches);
	}

	public Map<String, PropertyRule> conceptualRules() {
		return RULES;
	}

	private static Map<String, PropertyRule> rules() {
		Map<String, PropertyRule> rules = new LinkedHashMap<>();
		add(rules, JOB_TITLE, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.TITLE);
		add(rules, SOURCE, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.SELECT);
		add(rules, EXTERNAL_JOB_ID, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.RICH_TEXT);
		add(rules, CANONICAL_URL, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK,
			NotionPropertyType.URL, NotionPropertyType.RICH_TEXT);
		add(rules, JOB_URL, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.URL);
		add(rules, COMPANY, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.RICH_TEXT);
		add(rules, STATUS, Owner.USER, Mutability.CREATE_ONLY, NotionPropertyType.STATUS, NotionPropertyType.SELECT);
		add(rules, USER_NOTES, Owner.USER, Mutability.NEVER, NotionPropertyType.RICH_TEXT);
		add(rules, APPLIED_AT, Owner.USER, Mutability.NEVER, NotionPropertyType.DATE);
		add(rules, PUBLISHED_AT, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.DATE);
		add(rules, FIRST_SYNCED_AT, Owner.BACKEND, Mutability.CREATE_ONLY, NotionPropertyType.DATE);
		add(rules, SYNC_OPERATION_ID, Owner.BACKEND, Mutability.CREATE_ONLY, NotionPropertyType.RICH_TEXT);
		add(rules, LAST_SYNCED_AT, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.DATE);
		add(rules, WORK_MODE, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK,
			NotionPropertyType.SELECT, NotionPropertyType.RICH_TEXT);
		add(rules, LOCATION, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.RICH_TEXT);
		add(rules, ELIGIBILITY, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK,
			NotionPropertyType.SELECT, NotionPropertyType.RICH_TEXT);
		add(rules, SENIORITY, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK,
			NotionPropertyType.SELECT, NotionPropertyType.RICH_TEXT);
		add(rules, TECHNOLOGIES, Owner.BACKEND, Mutability.UPDATE_WHEN_NON_BLANK, NotionPropertyType.MULTI_SELECT);
		return Map.copyOf(rules);
	}

	private static void add(Map<String, PropertyRule> rules, String name, Owner owner, Mutability mutability,
		NotionPropertyType first, NotionPropertyType... rest) {
		EnumSet<NotionPropertyType> types = EnumSet.of(first, rest);
		rules.put(name, new PropertyRule(name, Set.copyOf(types), owner, mutability));
	}

	public record PropertyRule(String name, Set<NotionPropertyType> types, Owner owner, Mutability mutability) { }
	public record SchemaMismatch(String property, Set<NotionPropertyType> expectedTypes,
		NotionPropertyType actualType, String reason) { }

	public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {
		record Valid() implements ValidationResult { }
		record Invalid(List<SchemaMismatch> mismatches) implements ValidationResult {
			public Invalid { mismatches = List.copyOf(mismatches); }
		}
	}

	public enum Owner { BACKEND, USER }
	public enum Mutability { CREATE_ONLY, UPDATE_WHEN_NON_BLANK, NEVER }
}
