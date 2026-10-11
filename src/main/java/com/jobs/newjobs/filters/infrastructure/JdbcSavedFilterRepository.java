package com.jobs.newjobs.filters.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.jobs.newjobs.filters.application.SavedFilterRepository;
import com.jobs.newjobs.filters.domain.FilterSpecification;
import com.jobs.newjobs.filters.domain.SavedFilter;
import com.jobs.newjobs.jobs.domain.JobSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcSavedFilterRepository implements SavedFilterRepository {
	private final JdbcClient jdbcClient;
	private final JsonMapper jsonMapper;

	public JdbcSavedFilterRepository(JdbcClient jdbcClient, JsonMapper jsonMapper) {
		this.jdbcClient = jdbcClient;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public SavedFilter insert(SavedFilter filter) {
		jdbcClient.sql("""
			insert into saved_filter_configurations
				(id, name, source, filter_specification, version, created_at, updated_at)
			values
				(:id, :name, :source, cast(:specification as jsonb), :version, :createdAt, :updatedAt)
			""")
			.param("id", filter.id())
			.param("name", filter.name())
			.param("source", filter.source().name())
			.param("specification", serialize(filter.specification()))
			.param("version", filter.version())
			.param("createdAt", toUtcOffset(filter.createdAt()))
			.param("updatedAt", toUtcOffset(filter.updatedAt()))
			.update();
		return filter;
	}

	@Override
	public Optional<SavedFilter> findById(UUID id) {
		return jdbcClient.sql("""
			select id, name, source, filter_specification::text, version, created_at, updated_at
			from saved_filter_configurations
			where id = :id
			""")
			.param("id", id)
			.query((resultSet, rowNumber) -> map(resultSet))
			.optional();
	}

	@Override
	public List<SavedFilter> findAll() {
		return jdbcClient.sql("""
			select id, name, source, filter_specification::text, version, created_at, updated_at
			from saved_filter_configurations
			order by lower(name), id
			""")
			.query((resultSet, rowNumber) -> map(resultSet))
			.list();
	}

	@Override
	public boolean update(SavedFilter filter) {
		return jdbcClient.sql("""
			update saved_filter_configurations
			set name = :name,
				source = :source,
				filter_specification = cast(:specification as jsonb),
				version = :version,
				updated_at = :updatedAt
			where id = :id
			""")
			.param("id", filter.id())
			.param("name", filter.name())
			.param("source", filter.source().name())
			.param("specification", serialize(filter.specification()))
			.param("version", filter.version())
			.param("updatedAt", toUtcOffset(filter.updatedAt()))
			.update() == 1;
	}

	@Override
	public boolean deleteById(UUID id) {
		return jdbcClient.sql("delete from saved_filter_configurations where id = :id")
			.param("id", id)
			.update() == 1;
	}

	@Override
	public long count() {
		return jdbcClient.sql("select count(*) from saved_filter_configurations")
			.query(Long.class)
			.single();
	}

	private SavedFilter map(java.sql.ResultSet resultSet) throws java.sql.SQLException {
		return new SavedFilter(
			resultSet.getObject("id", UUID.class),
			resultSet.getString("name"),
			JobSource.valueOf(resultSet.getString("source")),
			deserialize(resultSet.getString("filter_specification")),
			resultSet.getLong("version"),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
			resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
		);
	}

	private static OffsetDateTime toUtcOffset(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private String serialize(FilterSpecification specification) {
		try {
			return jsonMapper.writeValueAsString(specification);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Could not serialize filter specification", exception);
		}
	}

	private FilterSpecification deserialize(String specification) {
		try {
			return jsonMapper.readValue(specification, FilterSpecification.class);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Could not deserialize persisted filter specification", exception);
		}
	}
}
