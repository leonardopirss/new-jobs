package com.jobs.newjobs.sync.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

@Component
public final class PostgresDestinationLock {
	private static final Duration MAX_ACQUISITION = Duration.ofSeconds(5);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

	private final DataSource dataSource;

	public PostgresDestinationLock(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public Optional<Lease> tryAcquire(String destinationId, Duration requestedBudget) {
		if (destinationId == null || destinationId.isBlank()) {
			throw new IllegalArgumentException("destination ID is required");
		}
		if (requestedBudget == null || requestedBudget.isNegative()) {
			throw new IllegalArgumentException("lock acquisition budget must not be negative");
		}
		Duration budget = requestedBudget.compareTo(MAX_ACQUISITION) > 0 ? MAX_ACQUISITION : requestedBudget;
		long key = lockKey(destinationId);
		Instant deadline = Instant.now().plus(budget);
		Connection connection = null;
		try {
			connection = dataSource.getConnection();
			connection.setAutoCommit(true);
			while (true) {
				if (tryLock(connection, key)) {
					return Optional.of(new Lease(connection, destinationId, key, backendPid(connection)));
				}
				if (!Instant.now().isBefore(deadline)) {
					connection.close();
					return Optional.empty();
				}
				Duration remaining = Duration.between(Instant.now(), deadline);
				Thread.sleep(Math.max(1, Math.min(POLL_INTERVAL.toMillis(), remaining.toMillis())));
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			closeQuietly(connection);
			throw new DestinationLockException("destination lock acquisition was interrupted", exception);
		}
		catch (SQLException exception) {
			closeQuietly(connection);
			throw new DestinationLockException("could not acquire destination lock", exception);
		}
	}

	private static boolean tryLock(Connection connection, long key) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
			statement.setLong(1, key);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}

	private static int backendPid(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_backend_pid()")) {
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) throw new SQLException("database did not return a backend PID");
				return result.getInt(1);
			}
		}
	}

	private static long lockKey(String destinationId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(("notion-destination\u0000" + destinationId).getBytes(StandardCharsets.UTF_8));
			return ByteBuffer.wrap(digest).getLong();
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void closeQuietly(Connection connection) {
		if (connection == null) return;
		try {
			connection.close();
		}
		catch (SQLException ignored) {
			// The database releases a session advisory lock when the connection is gone.
		}
	}

	public static final class Lease implements AutoCloseable {
		private final Connection connection;
		private final String destinationId;
		private final long key;
		private final int backendPid;
		private boolean closed;

		private Lease(Connection connection, String destinationId, long key, int backendPid) {
			this.connection = connection;
			this.destinationId = destinationId;
			this.key = key;
			this.backendPid = backendPid;
		}

		public String destinationId() {
			return destinationId;
		}

		public int backendPid() {
			return backendPid;
		}

		public void assertHeld() {
			if (closed) throw new DestinationLockLostException("destination lock is already closed");
			try {
				if (!connection.isValid(1)) {
					throw new DestinationLockLostException("destination lock connection is no longer valid");
				}
				try (PreparedStatement statement = connection.prepareStatement("""
					select exists (
					    select 1 from pg_locks
					    where locktype = 'advisory' and pid = pg_backend_pid() and granted
					)
					"""); ResultSet result = statement.executeQuery()) {
					if (!result.next() || !result.getBoolean(1)) {
						throw new DestinationLockLostException("destination advisory lock is no longer held");
					}
				}
			}
			catch (SQLException exception) {
				throw new DestinationLockLostException("destination lock connection was lost", exception);
			}
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			try {
				if (!connection.isClosed()) {
					try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
						statement.setLong(1, key);
						statement.execute();
					}
				}
			}
			catch (SQLException ignored) {
				// Closing the dedicated session releases the lock even when explicit unlock fails.
			}
			finally {
				closeQuietly(connection);
			}
		}
	}

	public static class DestinationLockException extends RuntimeException {
		public DestinationLockException(String message, Throwable cause) { super(message, cause); }
	}

	public static final class DestinationLockLostException extends DestinationLockException {
		public DestinationLockLostException(String message) { super(message, null); }
		public DestinationLockLostException(String message, Throwable cause) { super(message, cause); }
	}
}
