package com.jobs.newjobs.ranking.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.jobs.newjobs.jobs.domain.NormalizedJob;
import com.jobs.newjobs.ranking.domain.TechnologyAssessment;
import com.jobs.newjobs.ranking.domain.TechnologyEvidence;

@Component
public class TechnologyEvidenceExtractor {
	private static final Map<String, Set<String>> KNOWN_ALIASES = Map.of(
		"kubernetes", Set.of("kubernetes", "k8s"),
		"postgresql", Set.of("postgresql", "postgres", "postgre sql"),
		"javascript", Set.of("javascript", "java script", "js"),
		"typescript", Set.of("typescript", "type script", "ts"),
		"spring boot", Set.of("spring boot", "springboot"),
		"amazon web services", Set.of("amazon web services", "aws")
	);

	public List<TechnologyAssessment> assess(NormalizedJob job, List<String> technologies) {
		LinkedHashMap<String, TechnologyAssessment> assessments = new LinkedHashMap<>();
		for (String requested : technologies) {
			String canonical = canonical(requested);
			assessments.putIfAbsent(canonical, assess(job, canonical));
		}
		return List.copyOf(assessments.values());
	}

	public String canonical(String technology) {
		String normalized = normalize(technology);
		for (Map.Entry<String, Set<String>> entry : KNOWN_ALIASES.entrySet()) {
			if (entry.getValue().contains(normalized)) {
				return entry.getKey();
			}
		}
		return normalized;
	}

	private TechnologyAssessment assess(NormalizedJob job, String canonical) {
		Set<String> aliases = aliases(canonical);
		boolean structured = job.technologies().stream()
			.map(this::canonical)
			.anyMatch(canonical::equals);
		TextEvidence requirements = textEvidence(job.requirementsSummary(), aliases);
		TextEvidence responsibilities = textEvidence(String.join(" ", job.responsibilities()), aliases);

		List<String> reasons = new ArrayList<>();
		if (structured) {
			reasons.add("confirmed by the structured technology list");
		}
		if (requirements == TextEvidence.POSITIVE) {
			reasons.add("mentioned in targeted requirements text");
		}
		if (responsibilities == TextEvidence.POSITIVE) {
			reasons.add("mentioned in responsibilities text");
		}
		if (requirements == TextEvidence.NEGATIVE || responsibilities == TextEvidence.NEGATIVE) {
			reasons.add("explicitly negated in job-specific text");
		}

		boolean contradictoryText = requirements == TextEvidence.CONTRADICTORY
			|| responsibilities == TextEvidence.CONTRADICTORY;
		boolean positiveText = requirements == TextEvidence.POSITIVE || responsibilities == TextEvidence.POSITIVE;
		boolean negativeText = requirements == TextEvidence.NEGATIVE || responsibilities == TextEvidence.NEGATIVE;
		if (contradictoryText || (structured || positiveText) && negativeText) {
			return new TechnologyAssessment(canonical, TechnologyEvidence.UNKNOWN,
				List.of("equally relevant job evidence is contradictory"));
		}
		if (structured) {
			return new TechnologyAssessment(canonical, TechnologyEvidence.CONFIRMED, reasons);
		}
		if (positiveText) {
			return new TechnologyAssessment(canonical, TechnologyEvidence.MENTIONED, reasons);
		}
		if (negativeText) {
			return new TechnologyAssessment(canonical, TechnologyEvidence.NEGATED, reasons);
		}
		return new TechnologyAssessment(canonical, TechnologyEvidence.UNKNOWN,
			List.of("technology is not present in available job-specific evidence"));
	}

	private Set<String> aliases(String canonical) {
		return KNOWN_ALIASES.getOrDefault(canonical, Set.of(canonical));
	}

	private static TextEvidence textEvidence(String text, Set<String> aliases) {
		if (text == null || text.isBlank()) {
			return TextEvidence.NONE;
		}
		String normalized = normalize(text);
		boolean positive = false;
		boolean negative = false;
		for (String alias : new LinkedHashSet<>(aliases)) {
			String quoted = Pattern.quote(alias);
			String token = "(?<![\\p{L}\\p{N}])" + quoted + "(?![\\p{L}\\p{N}])";
			if (!Pattern.compile(token, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(normalized).find()) {
				continue;
			}
			boolean negated = Pattern.compile(
				"(?:\\bno\\b|\\bwithout\\b)\\s+(?:experience\\s+with\\s+)?" + token
					+ "|" + token + "\\s+(?:is\\s+)?(?:not\\s+used|not\\s+supported|excluded)",
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
			).matcher(normalized).find();
			if (negated) {
				negative = true;
			}
			else {
				positive = true;
			}
		}
		if (positive && negative) {
			return TextEvidence.CONTRADICTORY;
		}
		if (negative) {
			return TextEvidence.NEGATIVE;
		}
		return positive ? TextEvidence.POSITIVE : TextEvidence.NONE;
	}

	private static String normalize(String value) {
		return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	private enum TextEvidence {
		POSITIVE,
		NEGATIVE,
		CONTRADICTORY,
		NONE
	}
}
