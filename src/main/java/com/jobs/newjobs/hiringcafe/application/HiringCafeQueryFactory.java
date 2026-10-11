package com.jobs.newjobs.hiringcafe.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.filters.domain.CommonFilterField;
import com.jobs.newjobs.filters.domain.FilterGroup;
import com.jobs.newjobs.filters.domain.FilterOperation;
import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.filters.domain.MatchMode;
import com.jobs.newjobs.filters.domain.ProviderFilterGroup;
import com.jobs.newjobs.hiringcafe.config.HiringCafeProperties;

@Component
public class HiringCafeQueryFactory {
	private static final Set<String> ARRAY_ARGUMENTS = Set.of(
		"workplace_types", "employment_types", "seniority_levels", "company_names",
		"exclude_company_names", "departments", "language_requirements", "benefits_and_perks"
	);
	private static final Set<String> STRING_ARGUMENTS = Set.of("search", "location", "role_type", "salary_frequency");
	private static final Set<String> NUMBER_ARGUMENTS = Set.of("min_salary", "max_years_experience");
	private static final Map<String, Set<String>> ENUM_VALUES = Map.of(
		"workplace_types", Set.of("Remote", "Hybrid", "Onsite"),
		"employment_types", Set.of("Full Time", "Part Time", "Contract", "Internship", "Temporary", "Seasonal", "Volunteer"),
		"seniority_levels", Set.of("No Prior Experience Required", "Entry Level", "Mid Level", "Senior Level"),
		"role_type", Set.of("Individual Contributor", "People Manager"),
		"salary_frequency", Set.of("Yearly", "Monthly", "Weekly", "Daily", "Hourly"),
		"departments", Set.of(
			"Engineering", "Software Development", "Information Technology", "Data and Analytics", "Design",
			"Creative and Art Services", "Project and Program Management", "Product Management", "Business Operations",
			"Legal and Compliance", "Finance and Accounting", "Human Resources", "Administrative & Clerical Support",
			"Sales", "Marketing", "Communications and Public Affairs", "Business Development"
		),
		"benefits_and_perks", Set.of(
			"generous_paid_time_off", "four_day_work_week", "401k_matching", "generous_parental_leave",
			"retirement_plan", "tuition_reimbursement", "visa_sponsorship", "relocation_assistance"
		)
	);

	private final HiringCafeProperties properties;

	public HiringCafeQueryFactory(HiringCafeProperties properties) {
		this.properties = properties;
	}

	public Map<String, Object> create(FilterSpecification specification) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("posted_within_days", 1);
		arguments.put("limit", properties.resultLimit());

		for (FilterGroup group : specification.commonFilters()) {
			mapCommon(arguments, group);
		}
		for (ProviderFilterGroup group : specification.providerSpecificFilters()) {
			mapProviderSpecific(arguments, group);
		}
		return Map.copyOf(arguments);
	}

	private static void mapCommon(Map<String, Object> arguments, FilterGroup group) {
		String argument = switch (group.field()) {
			case TITLE, KEYWORD, TECHNOLOGY -> "search";
			case SENIORITY -> "seniority_levels";
			case EMPLOYMENT_TYPE -> "employment_types";
			case COUNTRY -> "location";
			case WORK_MODEL -> "workplace_types";
			case LANGUAGE -> "language_requirements";
			case COMPANY -> group.operation() == FilterOperation.EXCLUDE
				? "exclude_company_names" : "company_names";
			default -> throw new IllegalArgumentException(
				"Common filter " + group.field() + " has no verified HiringCafe mapping"
			);
		};

		if (group.operation() == FilterOperation.EXCLUDE && group.field() != CommonFilterField.COMPANY) {
			throw new IllegalArgumentException("HiringCafe does not document exclusion for " + group.field());
		}
		if (argument.equals("language_requirements")) {
			putUnique(arguments, argument, group.values());
			putUnique(arguments, "language_requirements_operator", group.matchMode() == MatchMode.ALL ? "AND" : "OR");
			return;
		}
		if (group.values().size() != 1) {
			throw new IllegalArgumentException(
				"HiringCafe does not document ALL/ANY semantics for multiple " + group.field() + " values"
			);
		}
		validateEnumValues(argument, group.values());
		Object value = ARRAY_ARGUMENTS.contains(argument) ? group.values() : group.values().getFirst();
		putUnique(arguments, argument, value);
	}

	private static void mapProviderSpecific(Map<String, Object> arguments, ProviderFilterGroup group) {
		String name = group.name();
		if (ARRAY_ARGUMENTS.contains(name)) {
			if (!name.equals("language_requirements") && group.values().size() != 1) {
				throw new IllegalArgumentException("HiringCafe does not document array matching semantics for " + name);
			}
			validateEnumValues(name, group.values());
			putUnique(arguments, name, group.values());
			if (name.equals("language_requirements")) {
				putUnique(arguments, "language_requirements_operator", group.matchMode() == MatchMode.ALL ? "AND" : "OR");
			}
			return;
		}
		if (group.values().size() != 1) {
			throw new IllegalArgumentException(name + " accepts exactly one value");
		}
		String value = group.values().getFirst();
		if (STRING_ARGUMENTS.contains(name)) {
			validateEnumValues(name, group.values());
			putUnique(arguments, name, value);
		}
		else if (NUMBER_ARGUMENTS.contains(name)) {
			putUnique(arguments, name, nonNegativeNumber(name, value));
		}
		else if (name.equals("posted_salary_only")) {
			if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
				throw new IllegalArgumentException("posted_salary_only must be true or false");
			}
			putUnique(arguments, name, Boolean.parseBoolean(value));
		}
		else {
			throw new IllegalArgumentException("Unsupported verified HiringCafe argument: " + name);
		}
	}

	private static void validateEnumValues(String name, List<String> values) {
		Set<String> allowed = ENUM_VALUES.get(name);
		if (allowed == null) {
			return;
		}
		for (String value : values) {
			if (!allowed.contains(value)) {
				throw new IllegalArgumentException("Unsupported HiringCafe " + name + " value: " + value);
			}
		}
	}

	private static BigDecimal nonNegativeNumber(String name, String value) {
		try {
			BigDecimal number = new BigDecimal(value);
			if (number.signum() < 0) {
				throw new IllegalArgumentException(name + " must not be negative");
			}
			return number;
		}
		catch (NumberFormatException exception) {
			throw new IllegalArgumentException(name + " must be numeric", exception);
		}
	}

	private static void putUnique(Map<String, Object> arguments, String name, Object value) {
		if (arguments.putIfAbsent(name, value) != null) {
			throw new IllegalArgumentException("HiringCafe argument " + name + " is configured more than once");
		}
	}
}
