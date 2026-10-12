package com.jobs.newjobs.shared.lifecycle;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.jobs.newjobs.scheduling.SchedulerProperties;

@Component
public final class GracefulWorkShutdown implements SmartLifecycle {
	private final Duration grace;
	private final AtomicBoolean accepting = new AtomicBoolean(true);
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicInteger active = new AtomicInteger();

	public GracefulWorkShutdown(SchedulerProperties properties) { this.grace = properties.shutdownGrace(); }

	public Optional<WorkPermit> begin(WorkKind kind) {
		if (!accepting.get()) return Optional.empty();
		active.incrementAndGet();
		if (!accepting.get()) {
			active.decrementAndGet();
			return Optional.empty();
		}
		return Optional.of(new WorkPermit(this, kind));
	}

	public boolean accepting() { return accepting.get(); }
	public int activeWork() { return active.get(); }

	@Override public void start() { accepting.set(true); running.set(true); }
	@Override public boolean isRunning() { return running.get(); }
	@Override public int getPhase() { return Integer.MAX_VALUE; }
	@Override public boolean isAutoStartup() { return true; }

	@Override
	public void stop() {
		accepting.set(false);
		awaitActiveWork();
		running.set(false);
	}

	@Override
	public void stop(Runnable callback) {
		accepting.set(false);
		Thread.ofPlatform().daemon().name("graceful-work-shutdown").start(() -> {
			try { awaitActiveWork(); }
			finally { running.set(false); callback.run(); }
		});
	}

	private void awaitActiveWork() {
		long deadline = System.nanoTime() + grace.toNanos();
		while (active.get() > 0 && System.nanoTime() < deadline) {
			try { TimeUnit.MILLISECONDS.sleep(10); }
			catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
		}
	}

	private void complete() { active.decrementAndGet(); }

	public enum WorkKind { MANUAL_SYNC, IMPORT, SCHEDULED_SYNC }

	public static final class WorkPermit implements AutoCloseable {
		private final GracefulWorkShutdown owner;
		private final WorkKind kind;
		private final AtomicBoolean closed = new AtomicBoolean();
		private WorkPermit(GracefulWorkShutdown owner, WorkKind kind) { this.owner = owner; this.kind = kind; }
		public WorkKind kind() { return kind; }
		@Override public void close() { if (closed.compareAndSet(false, true)) owner.complete(); }
	}
}
