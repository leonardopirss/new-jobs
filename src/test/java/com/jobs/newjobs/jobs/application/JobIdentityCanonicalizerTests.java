package com.jobs.newjobs.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer.CanonicalIdentity;
import com.jobs.newjobs.jobs.application.JobIdentityCanonicalizer.IdentityRelationship;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.jobs.domain.NormalizedJob;

class JobIdentityCanonicalizerTests {
	private final JobIdentityCanonicalizer canonicalizer = new JobIdentityCanonicalizer();

	@Test
	void canonicalizesOnlySafeUrlComponentsAndKnownTrackingParameters() {
		URI canonical = canonicalizer.canonicalizeUrl(URI.create(
			"HTTPS://EXAMPLE.COM:443/teams/../jobs/42?utm_source=mail&jobId=42&ref=direct&FBCLID=x#apply"
		));

		assertThat(canonical).hasToString("https://example.com/jobs/42?jobId=42&ref=direct");
	}

	@Test
	void preservesIdentityBearingParametersAndTheirOrder() {
		URI canonical = canonicalizer.canonicalizeUrl(URI.create(
			"https://jobs.example.test/opening?gh_jid=123&lever-origin=site&utm_campaign=spring"
		));

		assertThat(canonical).hasToString(
			"https://jobs.example.test/opening?gh_jid=123&lever-origin=site"
		);
	}

	@Test
	void rejectsUnsafeOrUnstableUrls() {
		assertThatThrownBy(() -> canonicalizer.canonicalizeUrl(URI.create("javascript:alert(1)")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> canonicalizer.canonicalizeUrl(URI.create("https://user@example.test/job")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void externalIdIsPrimaryButUrlRemainsAvailableForConflictDetection() {
		CanonicalIdentity left = canonicalizer.canonicalize(job(
			"same-id", "https://example.test/jobs/42?utm_source=one"
		));
		CanonicalIdentity same = canonicalizer.canonicalize(job(
			"same-id", "https://example.test/jobs/42?utm_source=two"
		));
		CanonicalIdentity conflictingUrl = canonicalizer.canonicalize(job(
			"same-id", "https://example.test/jobs/other"
		));

		assertThat(left.stableKey()).isEqualTo("HIRING_CAFE:id:same-id");
		assertThat(left.relationshipTo(same)).isEqualTo(IdentityRelationship.MATCH);
		assertThat(left.relationshipTo(conflictingUrl)).isEqualTo(IdentityRelationship.CONFLICT);
	}

	@Test
	void canonicalUrlIsTheFallbackIdentityAndSourceRemainsPartOfTheKey() {
		CanonicalIdentity identity = canonicalizer.canonicalize(job(
			null, "https://EXAMPLE.test/jobs/42?utm_medium=email"
		));

		assertThat(identity.stableKey()).isEqualTo("HIRING_CAFE:url:https://example.test/jobs/42");
		assertThat(identity.relationshipTo(new CanonicalIdentity(
			JobSource.LINKEDIN, null, URI.create("https://example.test/jobs/42")
		))).isEqualTo(IdentityRelationship.DISTINCT);
	}

	private static NormalizedJob job(String externalId, String url) {
		return new NormalizedJob(
			JobSource.HIRING_CAFE, externalId, "Engineer", "Example", null, null, List.of(), null,
			null, null, null, null, List.of(), List.of(), URI.create(url)
		);
	}
}
