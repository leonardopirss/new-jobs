package com.jobs.newjobs.imports.infrastructure;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobs.newjobs.imports.controller.ImportRequest;
import com.jobs.newjobs.imports.controller.ImportRequest.ImportedJob;
import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.sync.domain.SyncItemOutcome;
import com.jobs.newjobs.sync.domain.SyncRunResult;
import com.jobs.newjobs.sync.domain.SyncSummary;

import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcImportIdempotencyRepository {
	private static final Duration RETENTION = Duration.ofHours(48);
	private static final Duration RESERVED_STALE_AFTER = Duration.ofSeconds(5);
	private static final Duration PROCESSING_STALE_AFTER = Duration.ofSeconds(105);
	private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

	private final JdbcClient jdbc;
	private final TransactionTemplate transactions;

	public JdbcImportIdempotencyRepository(JdbcClient jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	public Reservation reserve(String integrationId, String key, String payloadHash, int receivedCount) {
		validate(integrationId, key, payloadHash, receivedCount);
		return Objects.requireNonNull(transactions.execute(status -> {
			Record existing = findForUpdate(integrationId, key);
			if (existing != null && existing.expired()) {
				delete(integrationId, key);
				existing = null;
			}
			if (existing == null) {
				Reservation.Owner inserted = insert(integrationId, key, payloadHash, receivedCount);
				if (inserted != null) return inserted;
				existing = findForUpdate(integrationId, key);
				if (existing == null) throw new IllegalStateException("idempotency reservation disappeared");
			}
			if (!MessageDigest.isEqual(existing.payloadHash().getBytes(StandardCharsets.US_ASCII),
				payloadHash.getBytes(StandardCharsets.US_ASCII))) {
				return new Reservation.Conflict(existing.requestId());
			}
			if (existing.state() == State.COMPLETED || existing.state() == State.PARTIAL) {
				return new Reservation.Replay(readOutcome(existing.outcomeDocument(), existing.receivedCount()));
			}
			if (existing.state() == State.FAILED_PRESTART) {
				return new Reservation.FailedPrestart(existing.requestId(), readFailure(existing.outcomeDocument()));
			}
			if (!existing.stale()) return new Reservation.InProgress(existing.requestId());

			String ownerToken = UUID.randomUUID().toString();
			State claimedState = existing.state() == State.RESERVED ? State.RESERVED : State.RECONCILIATION_REQUIRED;
			int changed = jdbc.sql("""
				update import_idempotency_records
				set state = :state, owner_token_hash = :ownerHash, updated_at = transaction_timestamp()
				where integration_id = :integrationId and idempotency_key = :key
				""")
				.param("state", claimedState.name())
				.param("ownerHash", sha256(ownerToken))
				.param("integrationId", integrationId)
				.param("key", key)
				.update();
			if (changed != 1) throw new IllegalStateException("idempotency takeover failed");
			return new Reservation.Owner(existing.requestId(), ownerToken,
				claimedState == State.RECONCILIATION_REQUIRED);
		}));
	}

	public void markProcessing(String integrationId, String key, String ownerToken) {
		transition(integrationId, key, ownerToken, State.PROCESSING, "state = 'RESERVED'", null);
	}

	public void markReconciliationRequired(String integrationId, String key, String ownerToken) {
		transition(integrationId, key, ownerToken, State.RECONCILIATION_REQUIRED,
			"state in ('PROCESSING', 'RECONCILIATION_REQUIRED')", null);
	}

	public void complete(String integrationId, String key, String ownerToken, SyncRunResult result) {
		Record record = find(integrationId, key);
		if (record == null || !result.requestId().equals(record.requestId())
			|| result.items().size() != record.receivedCount()) {
			throw new IllegalArgumentException("import outcome count does not match the reservation");
		}
		State target = result.status() == SyncRunResult.Status.COMPLETED ? State.COMPLETED : State.PARTIAL;
		transition(integrationId, key, ownerToken, target,
			"state in ('PROCESSING', 'RECONCILIATION_REQUIRED')", writeOutcome(result));
	}

	public void failPrestart(String integrationId, String key, String ownerToken, UUID requestId, String reasonCode) {
		String bounded = boundedReason(reasonCode);
		String document;
		try {
			document = JSON.writeValueAsString(new FailureDocument(requestId, bounded));
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("could not serialize pre-start outcome", exception);
		}
		transition(integrationId, key, ownerToken, State.FAILED_PRESTART,
			"state in ('RESERVED', 'PROCESSING')", document);
	}

	public Reservation await(String integrationId, String key, String payloadHash, Duration budget) {
		long remainingNanos = budget.toNanos();
		long last = System.nanoTime();
		while (remainingNanos > 0) {
			Record record = find(integrationId, key);
			if (record == null) return new Reservation.InProgress(null);
			if (!record.payloadHash().equals(payloadHash)) return new Reservation.Conflict(record.requestId());
			if (record.state() == State.COMPLETED || record.state() == State.PARTIAL) {
				return new Reservation.Replay(readOutcome(record.outcomeDocument(), record.receivedCount()));
			}
			if (record.state() == State.FAILED_PRESTART) {
				return new Reservation.FailedPrestart(record.requestId(), readFailure(record.outcomeDocument()));
			}
			try { Thread.sleep(Math.min(25, Math.max(1, Duration.ofNanos(remainingNanos).toMillis()))); }
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return new Reservation.InProgress(record.requestId());
			}
			long now = System.nanoTime();
			remainingNanos -= now - last;
			last = now;
		}
		Record record = find(integrationId, key);
		return new Reservation.InProgress(record == null ? null : record.requestId());
	}

	public static String canonicalPayloadHash(JobSource source, ImportRequest request) {
		MessageDigest digest = digest();
		put(digest, source == null ? null : source.name());
		put(digest, Integer.toString(request.jobs().size()));
		for (ImportedJob job : request.jobs()) {
			if (job == null) { put(digest, (String) null); continue; }
			put(digest, job.externalJobId()); put(digest, job.title()); put(digest, job.company());
			put(digest, job.url() == null ? null : job.url().toString()); put(digest, job.location());
			put(digest, job.workplaceType()); put(digest, job.employmentTypes());
			put(digest, job.publishedAt() == null ? null : job.publishedAt().toString());
			put(digest, job.requirementsSummary()); put(digest, job.seniorityLevel());
			BigDecimal years = job.minimumYearsExperience();
			put(digest, years == null ? null : years.stripTrailingZeros().toPlainString());
			put(digest, job.responsibilities()); put(digest, job.technologies());
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private Reservation.Owner insert(String integrationId, String key, String payloadHash, int receivedCount) {
		UUID requestId = UUID.randomUUID();
		String ownerToken = UUID.randomUUID().toString();
		int inserted = jdbc.sql("""
			insert into import_idempotency_records (
			  integration_id, idempotency_key, payload_hash, state, request_id, owner_token_hash,
			  received_count, created_at, updated_at, expires_at
			) values (
			  :integrationId, :key, :payloadHash, 'RESERVED', :requestId, :ownerHash,
			  :receivedCount, transaction_timestamp(), transaction_timestamp(),
			  transaction_timestamp() + interval '48 hours'
			)
			on conflict (integration_id, idempotency_key) do nothing
			""")
			.param("integrationId", integrationId).param("key", key).param("payloadHash", payloadHash)
			.param("requestId", requestId).param("ownerHash", sha256(ownerToken))
			.param("receivedCount", receivedCount).update();
		return inserted == 1 ? new Reservation.Owner(requestId, ownerToken, false) : null;
	}

	private void transition(String integrationId, String key, String ownerToken, State target,
		String statePredicate, String outcomeDocument) {
		int changed = jdbc.sql("""
			update import_idempotency_records
			set state = :target, owner_token_hash = :nextOwner,
			    outcome_document = cast(:outcome as jsonb), updated_at = transaction_timestamp()
			where integration_id = :integrationId and idempotency_key = :key
			  and owner_token_hash = :ownerHash and %s
			""".formatted(statePredicate))
			.param("target", target.name())
			.param("nextOwner", terminal(target) ? null : sha256(ownerToken), java.sql.Types.CHAR)
			.param("outcome", outcomeDocument, java.sql.Types.VARCHAR)
			.param("integrationId", integrationId).param("key", key).param("ownerHash", sha256(ownerToken))
			.update();
		if (changed != 1) throw new IllegalStateException("invalid import idempotency transition to " + target);
	}

	private Record findForUpdate(String integrationId, String key) {
		return query(integrationId, key, true);
	}

	private Record find(String integrationId, String key) {
		return query(integrationId, key, false);
	}

	private Record query(String integrationId, String key, boolean forUpdate) {
		return jdbc.sql("""
			select integration_id, idempotency_key, payload_hash, state, request_id, received_count,
			       outcome_document::text as outcome_document, updated_at, expires_at,
			       transaction_timestamp() as database_now
			from import_idempotency_records
			where integration_id = :integrationId and idempotency_key = :key
			%s
			""".formatted(forUpdate ? "for update" : ""))
			.param("integrationId", integrationId).param("key", key)
			.query((resultSet, rowNumber) -> map(resultSet)).optional().orElse(null);
	}

	private void delete(String integrationId, String key) {
		jdbc.sql("delete from import_idempotency_records where integration_id = :integrationId and idempotency_key = :key")
			.param("integrationId", integrationId).param("key", key).update();
	}

	private static Record map(ResultSet resultSet) throws SQLException {
		return new Record(resultSet.getString("payload_hash").trim(), State.valueOf(resultSet.getString("state")),
			resultSet.getObject("request_id", UUID.class), resultSet.getInt("received_count"),
			resultSet.getString("outcome_document"),
			resultSet.getObject("updated_at", OffsetDateTime.class),
			resultSet.getObject("expires_at", OffsetDateTime.class),
			resultSet.getObject("database_now", OffsetDateTime.class));
	}

	private static SyncRunResult readOutcome(String json, int receivedCount) {
		try {
			SyncRunResult result = JSON.readValue(json, SyncRunResult.class);
			validateOutcome(result);
			if (result.items().size() != receivedCount) {
				throw new IllegalStateException("stored import outcome count is inconsistent");
			}
			return result;
		}
		catch (RuntimeException exception) { throw new IllegalStateException("stored import outcome is invalid", exception); }
	}

	private static String writeOutcome(SyncRunResult result) {
		validateOutcome(result);
		try { return JSON.writeValueAsString(result); }
		catch (RuntimeException exception) { throw new IllegalStateException("could not serialize import outcome", exception); }
	}

	private static String readFailure(String json) {
		try { return JSON.readValue(json, FailureDocument.class).reasonCode(); }
		catch (RuntimeException exception) { throw new IllegalStateException("stored pre-start outcome is invalid", exception); }
	}

	private static void validateOutcome(SyncRunResult result) {
		List<SyncItemOutcome> items = result.items();
		for (int index = 0; index < items.size(); index++) {
			SyncItemOutcome item = items.get(index);
			if (item.index() != index) throw new IllegalArgumentException("import outcome indices must be contiguous");
			if ((item.status().name().equals("INVALID") || item.status().name().equals("CONFLICTED")
				|| item.status().name().equals("UNCERTAIN") || item.status().name().equals("NOT_PROCESSED"))
				&& (item.reasonCode() == null || item.reasonCode().isBlank())) {
				throw new IllegalArgumentException("failed import outcomes require a reason code");
			}
		}
		SyncSummary expected = SyncSummary.from(items);
		if (!expected.equals(result.summary()) || expected.received() != items.size()) {
			throw new IllegalArgumentException("import outcome summary is inconsistent");
		}
		if ((result.status() == SyncRunResult.Status.COMPLETED) != expected.complete()) {
			throw new IllegalArgumentException("import outcome status is inconsistent");
		}
	}

	private static void validate(String integrationId, String key, String hash, int count) {
		if (integrationId == null || integrationId.isBlank()) throw new IllegalArgumentException("integration ID is required");
		if (key == null || key.length() < 8 || key.length() > 255) throw new IllegalArgumentException("invalid idempotency key");
		if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid payload hash");
		if (count < 1 || count > 50) throw new IllegalArgumentException("invalid received count");
	}

	private static String boundedReason(String value) {
		String safe = value == null ? "PRESTART_FAILED" : value.replaceAll("[^A-Z0-9_.-]", "_");
		return safe.substring(0, Math.min(64, safe.length()));
	}

	private static boolean terminal(State state) {
		return state == State.COMPLETED || state == State.PARTIAL || state == State.FAILED_PRESTART;
	}

	private static MessageDigest digest() {
		try { return MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
	}

	private static String sha256(String value) {
		return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private static void put(MessageDigest digest, String value) {
		if (value == null) { digest.update(ByteBuffer.allocate(4).putInt(-1).array()); return; }
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
	}

	private static void put(MessageDigest digest, List<String> values) {
		if (values == null) { put(digest, (String) null); return; }
		put(digest, Integer.toString(values.size()));
		values.forEach(value -> put(digest, value));
	}

	public sealed interface Reservation permits Reservation.Owner, Reservation.Replay,
		Reservation.Conflict, Reservation.InProgress, Reservation.FailedPrestart {
		record Owner(UUID requestId, String ownerToken, boolean reconciliation) implements Reservation { }
		record Replay(SyncRunResult result) implements Reservation { }
		record Conflict(UUID requestId) implements Reservation { }
		record InProgress(UUID requestId) implements Reservation { }
		record FailedPrestart(UUID requestId, String reasonCode) implements Reservation { }
	}

	private enum State { RESERVED, PROCESSING, COMPLETED, PARTIAL, FAILED_PRESTART, RECONCILIATION_REQUIRED }
	private record FailureDocument(UUID requestId, String reasonCode) { }
	private record Record(String payloadHash, State state, UUID requestId, int receivedCount, String outcomeDocument,
		OffsetDateTime updatedAt, OffsetDateTime expiresAt, OffsetDateTime databaseNow) {
		boolean expired() { return !expiresAt.isAfter(databaseNow); }
		boolean stale() {
			Duration threshold = state == State.RESERVED ? RESERVED_STALE_AFTER : PROCESSING_STALE_AFTER;
			return !updatedAt.isAfter(databaseNow.minus(threshold));
		}
	}
}
