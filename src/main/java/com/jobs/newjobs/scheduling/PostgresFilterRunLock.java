package com.jobs.newjobs.scheduling;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

@Component
public final class PostgresFilterRunLock {
	private final DataSource dataSource;
	public PostgresFilterRunLock(DataSource dataSource) { this.dataSource = dataSource; }

	public Optional<Lease> tryAcquire(UUID filterId) {
		if (filterId == null) throw new IllegalArgumentException("filter ID is required");
		Connection connection = null;
		try {
			connection = dataSource.getConnection();
			connection.setAutoCommit(true);
			long key = lockKey(filterId);
			try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
				statement.setLong(1, key);
				try (ResultSet result = statement.executeQuery()) {
					if (result.next() && result.getBoolean(1)) return Optional.of(new Lease(connection, key));
				}
			}
			connection.close();
			return Optional.empty();
		}
		catch (SQLException exception) {
			closeQuietly(connection);
			throw new FilterRunLockException("could not decide saved-filter run ownership", exception);
		}
	}

	private static long lockKey(UUID filterId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(("saved-filter-run\u0000" + filterId).getBytes(StandardCharsets.UTF_8));
			return ByteBuffer.wrap(digest).getLong();
		}
		catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
	}

	private static void closeQuietly(Connection connection) {
		if (connection == null) return;
		try { connection.close(); } catch (SQLException ignored) { }
	}

	public static final class Lease implements AutoCloseable {
		private final Connection connection;
		private final long key;
		private boolean closed;
		private Lease(Connection connection, long key) { this.connection = connection; this.key = key; }
		@Override public void close() {
			if (closed) return;
			closed = true;
			try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
				statement.setLong(1, key); statement.execute();
			}
			catch (SQLException ignored) { }
			finally { closeQuietly(connection); }
		}
	}

	public static final class FilterRunLockException extends RuntimeException {
		public FilterRunLockException(String message, Throwable cause) { super(message, cause); }
	}
}
