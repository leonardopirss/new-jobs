package com.jobs.newjobs.sync.infrastructure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.jobs.newjobs.jobs.domain.JobSource;
import com.jobs.newjobs.sync.application.SyncSafetyMarkerRepository;
import com.jobs.newjobs.sync.domain.SyncMarkerState;
import com.jobs.newjobs.sync.domain.SyncSafetyMarker;

@Repository
public class JdbcSyncSafetyMarkerRepository implements SyncSafetyMarkerRepository {
	private final JdbcClient jdbcClient;
	private final TransactionTemplate transactions;

	public JdbcSyncSafetyMarkerRepository(JdbcClient jdbcClient, TransactionTemplate transactions) {
		this.jdbcClient = jdbcClient;
		this.transactions = transactions;
	}

	@Override
	public PrepareResult prepare(CreateIntent intent) {
		String externalHash = identityHash(intent.source(), intent.externalJobId());
		String urlHash = identityHash(intent.source(), intent.canonicalUrl() == null ? null : intent.canonicalUrl().toString());
		try {
			return Objects.requireNonNull(transactions.execute(status -> {
				List<SyncSafetyMarker> matches = findActiveMatches(intent.destinationId(), intent.source(), externalHash, urlHash);
				PrepareResult existing = classify(matches, externalHash, urlHash);
				if (existing != null) {
					return existing;
				}
				insert(intent, externalHash, urlHash);
				return new PrepareResult.Prepared(find(intent.operationId()).orElseThrow());
			}));
		}
		catch (DuplicateKeyException race) {
			List<SyncSafetyMarker> matches = findActiveMatches(intent.destinationId(), intent.source(), externalHash, urlHash);
			PrepareResult classified = classify(matches, externalHash, urlHash);
			if (classified != null) return classified;
			throw race;
		}
	}

	@Override
	public SyncSafetyMarker markInFlight(UUID operationId, Instant at) {
		return transition(operationId, SyncMarkerState.IN_FLIGHT, null, null, at, false,
			SyncMarkerState.PREPARED);
	}

	@Override
	public SyncSafetyMarker confirm(UUID operationId, String notionPageId, Instant at) {
		if (notionPageId == null || notionPageId.isBlank()) {
			throw new IllegalArgumentException("confirmed Notion page ID is required");
		}
		return transition(operationId, SyncMarkerState.CONFIRMED, notionPageId, null, at, true,
			SyncMarkerState.PREPARED, SyncMarkerState.IN_FLIGHT, SyncMarkerState.UNCERTAIN, SyncMarkerState.CONFLICT);
	}

	@Override
	public SyncSafetyMarker markNotSent(UUID operationId, String failureCode, Instant at) {
		return transition(operationId, SyncMarkerState.NOT_SENT, null, failureCode, at, true,
			SyncMarkerState.PREPARED, SyncMarkerState.IN_FLIGHT, SyncMarkerState.UNCERTAIN, SyncMarkerState.CONFLICT);
	}

	@Override
	public SyncSafetyMarker markUncertain(UUID operationId, String failureCode, Instant at) {
		return transition(operationId, SyncMarkerState.UNCERTAIN, null, failureCode, at, false,
			SyncMarkerState.IN_FLIGHT);
	}

	@Override
	public SyncSafetyMarker markConflict(UUID operationId, String failureCode, Instant at) {
		return transition(operationId, SyncMarkerState.CONFLICT, null, failureCode, at, false,
			SyncMarkerState.PREPARED, SyncMarkerState.IN_FLIGHT, SyncMarkerState.UNCERTAIN);
	}

	@Override
	public Optional<SyncSafetyMarker> find(UUID operationId) {
		return jdbcClient.sql("""
			select operation_id, destination_id, source, external_job_id, canonical_url,
			       external_id_hash, canonical_url_hash, state, notion_page_id, failure_code,
			       created_at, updated_at, resolved_at
			from sync_safety_markers
			where operation_id = :operationId
			""")
			.param("operationId", operationId)
			.query((resultSet, rowNumber) -> map(resultSet))
			.optional();
	}

	@Override
	public List<SyncSafetyMarker> findUnresolved(String destinationId) {
		return jdbcClient.sql("""
			select operation_id, destination_id, source, external_job_id, canonical_url,
			       external_id_hash, canonical_url_hash, state, notion_page_id, failure_code,
			       created_at, updated_at, resolved_at
			from sync_safety_markers
			where destination_id = :destinationId
			  and state in ('PREPARED', 'IN_FLIGHT', 'UNCERTAIN', 'CONFLICT')
			order by created_at, operation_id
			""")
			.param("destinationId", destinationId)
			.query((resultSet, rowNumber) -> map(resultSet))
			.list();
	}

	private void insert(CreateIntent intent, String externalHash, String urlHash) {
		jdbcClient.sql("""
			insert into sync_safety_markers
			    (operation_id, destination_id, source, external_job_id, canonical_url,
			     external_id_hash, canonical_url_hash, operation_type, state,
			     created_at, updated_at)
			values
			    (:operationId, :destinationId, :source, :externalJobId, :canonicalUrl,
			     :externalHash, :urlHash, 'CREATE', 'PREPARED', :createdAt, :createdAt)
			""")
			.param("operationId", intent.operationId())
			.param("destinationId", intent.destinationId())
			.param("source", intent.source().name())
			.param("externalJobId", intent.externalJobId(), Types.VARCHAR)
			.param("canonicalUrl", intent.canonicalUrl() == null ? null : intent.canonicalUrl().toString(), Types.VARCHAR)
			.param("externalHash", externalHash, Types.CHAR)
			.param("urlHash", urlHash, Types.CHAR)
			.param("createdAt", utc(intent.createdAt()))
			.update();
	}

	private List<SyncSafetyMarker> findActiveMatches(String destinationId, JobSource source,
		String externalHash, String urlHash) {
		String identityClause;
		if (externalHash != null && urlHash != null) {
			identityClause = "(external_id_hash = :externalHash or canonical_url_hash = :urlHash)";
		}
		else if (externalHash != null) {
			identityClause = "external_id_hash = :externalHash";
		}
		else {
			identityClause = "canonical_url_hash = :urlHash";
		}
		var statement = jdbcClient.sql("""
			select operation_id, destination_id, source, external_job_id, canonical_url,
			       external_id_hash, canonical_url_hash, state, notion_page_id, failure_code,
			       created_at, updated_at, resolved_at
			from sync_safety_markers
			where destination_id = :destinationId and source = :source
			  and state in ('PREPARED', 'IN_FLIGHT', 'UNCERTAIN', 'CONFLICT')
			  and %s
			order by created_at, operation_id
			""".formatted(identityClause))
			.param("destinationId", destinationId)
			.param("source", source.name());
		if (externalHash != null) statement = statement.param("externalHash", externalHash);
		if (urlHash != null) statement = statement.param("urlHash", urlHash);
		return statement.query((resultSet, rowNumber) -> map(resultSet)).list();
	}

	private static PrepareResult classify(List<SyncSafetyMarker> matches, String externalHash, String urlHash) {
		if (matches.isEmpty()) return null;
		if (matches.size() != 1) return new PrepareResult.Conflict(matches);
		SyncSafetyMarker marker = matches.getFirst();
		boolean externalConflict = externalHash != null && marker.externalIdHash() != null
			&& !externalHash.equals(marker.externalIdHash());
		boolean urlConflict = urlHash != null && marker.canonicalUrlHash() != null
			&& !urlHash.equals(marker.canonicalUrlHash());
		return externalConflict || urlConflict
			? new PrepareResult.Conflict(matches)
			: new PrepareResult.Existing(marker);
	}

	private SyncSafetyMarker transition(UUID operationId, SyncMarkerState target, String pageId,
		String failureCode, Instant at, boolean resolved, SyncMarkerState... allowed) {
		String allowedSql = java.util.Arrays.stream(allowed).map(state -> "'" + state.name() + "'")
			.collect(java.util.stream.Collectors.joining(", "));
		int changed = jdbcClient.sql("""
			update sync_safety_markers
			set state = :target,
			    notion_page_id = coalesce(:pageId, notion_page_id),
			    failure_code = :failureCode,
			    updated_at = :at,
			    resolved_at = :resolvedAt
			where operation_id = :operationId and state in (%s)
			""".formatted(allowedSql))
			.param("target", target.name())
			.param("pageId", pageId, Types.VARCHAR)
			.param("failureCode", boundedFailure(failureCode), Types.VARCHAR)
			.param("at", utc(at))
			.param("resolvedAt", resolved ? utc(at) : null, Types.TIMESTAMP_WITH_TIMEZONE)
			.param("operationId", operationId)
			.update();
		if (changed != 1) {
			throw new IllegalStateException("invalid synchronization marker transition to " + target);
		}
		return find(operationId).orElseThrow();
	}

	private static SyncSafetyMarker map(ResultSet resultSet) throws SQLException {
		String url = resultSet.getString("canonical_url");
		OffsetDateTime resolvedAt = resultSet.getObject("resolved_at", OffsetDateTime.class);
		return new SyncSafetyMarker(
			resultSet.getObject("operation_id", UUID.class),
			resultSet.getString("destination_id"),
			JobSource.valueOf(resultSet.getString("source")),
			resultSet.getString("external_job_id"),
			url == null ? null : URI.create(url),
			trim(resultSet.getString("external_id_hash")),
			trim(resultSet.getString("canonical_url_hash")),
			SyncMarkerState.valueOf(resultSet.getString("state")),
			resultSet.getString("notion_page_id"),
			resultSet.getString("failure_code"),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
			resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
			resolvedAt == null ? null : resolvedAt.toInstant()
		);
	}

	private static String identityHash(JobSource source, String value) {
		if (value == null || value.isBlank()) return null;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest((source.name() + "\u0000" + value)
				.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "transition time is required"), ZoneOffset.UTC);
	}

	private static String boundedFailure(String failureCode) {
		if (failureCode == null || failureCode.isBlank()) return null;
		String safe = failureCode.replaceAll("[^A-Za-z0-9_.-]", "_");
		return safe.substring(0, Math.min(64, safe.length()));
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}
}
