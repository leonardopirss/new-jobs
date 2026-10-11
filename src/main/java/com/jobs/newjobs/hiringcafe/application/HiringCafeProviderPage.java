package com.jobs.newjobs.hiringcafe.application;

import java.util.List;

public record HiringCafeProviderPage(
	List<HiringCafeProviderJob> jobs,
	int count,
	Long totalMatches
) {
	public HiringCafeProviderPage {
		jobs = List.copyOf(jobs);
	}
}
