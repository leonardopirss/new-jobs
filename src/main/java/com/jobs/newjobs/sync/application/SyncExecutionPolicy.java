package com.jobs.newjobs.sync.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public record SyncExecutionPolicy(
	UUID requestId,
	Instant deadline,
	Duration lockBudget,
	Duration minimumStartBudget
) {
	public SyncExecutionPolicy {
		if (requestId == null || lockBudget == null || lockBudget.isNegative()
			|| minimumStartBudget == null || minimumStartBudget.isNegative()) {
			throw new IllegalArgumentException("sync execution policy is invalid");
		}
	}

	public boolean canStart(Instant now) {
		return deadline == null || !now.plus(minimumStartBudget).isAfter(deadline);
	}

	public Duration effectiveLockBudget(Instant now) {
		if (deadline == null) return lockBudget;
		Duration remaining = Duration.between(now, deadline);
		if (remaining.isNegative()) return Duration.ZERO;
		return remaining.compareTo(lockBudget) < 0 ? remaining : lockBudget;
	}
}
