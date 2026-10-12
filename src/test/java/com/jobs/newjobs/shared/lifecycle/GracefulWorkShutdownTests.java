package com.jobs.newjobs.shared.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.jobs.newjobs.scheduling.SchedulerProperties;
import com.jobs.newjobs.shared.lifecycle.GracefulWorkShutdown.WorkKind;

class GracefulWorkShutdownTests {
	@Test
	void stopsNewAdmissionsAndLetsStartedWorkFinishBeforeCallback() throws Exception {
		GracefulWorkShutdown shutdown = shutdown(Duration.ofSeconds(1));
		var permit = shutdown.begin(WorkKind.IMPORT).orElseThrow();
		CountDownLatch stopped = new CountDownLatch(1);

		shutdown.stop(stopped::countDown);

		assertThat(shutdown.accepting()).isFalse();
		assertThat(shutdown.begin(WorkKind.MANUAL_SYNC)).isEmpty();
		assertThat(stopped.await(40, TimeUnit.MILLISECONDS)).isFalse();
		permit.close();
		assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(shutdown.activeWork()).isZero();
	}

	@Test
	void boundsShutdownWhenExternalWorkCannotFinish() throws Exception {
		GracefulWorkShutdown shutdown = shutdown(Duration.ofMillis(40));
		var permit = shutdown.begin(WorkKind.SCHEDULED_SYNC).orElseThrow();
		CountDownLatch stopped = new CountDownLatch(1);

		shutdown.stop(stopped::countDown);

		assertThat(stopped.await(1, TimeUnit.SECONDS)).isTrue();
		assertThat(shutdown.isRunning()).isFalse();
		permit.close();
	}

	private static GracefulWorkShutdown shutdown(Duration grace) {
		return new GracefulWorkShutdown(new SchedulerProperties(false, false, Duration.ofHours(6),
			ZoneId.of("UTC"), grace));
	}
}
