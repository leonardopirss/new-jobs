package com.jobs.newjobs.imports.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

@Component
public final class JdbcImportRateLimiter {
	private static final int MAX_ADMISSIONS = 10;
	private static final Duration WINDOW = Duration.ofSeconds(60);
	private static final Duration RETENTION = Duration.ofMinutes(5);

	private final DataSource dataSource;

	public JdbcImportRateLimiter(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public AdmissionDecision tryAdmit(String integrationId, UUID admissionId) {
		if (integrationId == null || integrationId.isBlank()) {
			throw new IllegalArgumentException("integration ID is required");
		}
		if (admissionId == null) throw new IllegalArgumentException("admission ID is required");

		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try {
				acquireLock(connection, lockKey(integrationId));
				Instant databaseNow = databaseNow(connection);
				deleteExpired(connection, integrationId, databaseNow.minus(RETENTION));
				WindowState state = activeWindow(connection, integrationId, databaseNow.minus(WINDOW));
				if (state.count() >= MAX_ADMISSIONS) {
					connection.commit();
					long seconds = Math.max(1, (long) Math.ceil(
						Duration.between(databaseNow, state.oldest().plus(WINDOW)).toMillis() / 1000.0));
					return new AdmissionDecision(false, Duration.ofSeconds(seconds));
				}
				insert(connection, integrationId, admissionId, databaseNow);
				connection.commit();
				return new AdmissionDecision(true, Duration.ZERO);
			}
			catch (RuntimeException | SQLException exception) {
				rollbackQuietly(connection);
				throw exception;
			}
		}
		catch (SQLException exception) {
			throw new ImportRateLimitException("could not decide import admission", exception);
		}
	}

	private static void acquireLock(Connection connection, long key) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
			statement.setLong(1, key);
			statement.execute();
		}
	}

	private static Instant databaseNow(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select transaction_timestamp()")) {
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) throw new SQLException("database clock was unavailable");
				return result.getTimestamp(1).toInstant();
			}
		}
	}

	private static void deleteExpired(Connection connection, String integrationId, Instant cutoff) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"delete from import_rate_admissions where integration_id = ? and admitted_at <= ?")) {
			statement.setString(1, integrationId);
			statement.setTimestamp(2, Timestamp.from(cutoff));
			statement.executeUpdate();
		}
	}

	private static WindowState activeWindow(Connection connection, String integrationId, Instant cutoff)
		throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			select count(*), min(admitted_at)
			from import_rate_admissions
			where integration_id = ? and admitted_at > ?
			""")) {
			statement.setString(1, integrationId);
			statement.setTimestamp(2, Timestamp.from(cutoff));
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) throw new SQLException("rate window query returned no row");
				long count = result.getLong(1);
				Timestamp oldest = result.getTimestamp(2);
				return new WindowState(count, oldest == null ? null : oldest.toInstant());
			}
		}
	}

	private static void insert(Connection connection, String integrationId, UUID admissionId, Instant admittedAt)
		throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			insert into import_rate_admissions (admission_id, integration_id, admitted_at)
			values (?, ?, ?)
			""")) {
			statement.setObject(1, admissionId);
			statement.setString(2, integrationId);
			statement.setTimestamp(3, Timestamp.from(admittedAt));
			statement.executeUpdate();
		}
	}

	private static long lockKey(String integrationId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(("import-rate\u0000" + integrationId).getBytes(StandardCharsets.UTF_8));
			return ByteBuffer.wrap(digest).getLong();
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void rollbackQuietly(Connection connection) {
		try { connection.rollback(); }
		catch (SQLException ignored) { }
	}

	public record AdmissionDecision(boolean admitted, Duration retryAfter) { }
	private record WindowState(long count, Instant oldest) { }

	public static final class ImportRateLimitException extends RuntimeException {
		public ImportRateLimitException(String message, Throwable cause) { super(message, cause); }
	}
}
