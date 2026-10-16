package dev.repomind.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngestJobDao {

	private static final RowMapper<IngestJob> MAPPER = IngestJobDao::map;

	private final JdbcClient jdbc;

	public IngestJobDao(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public IngestJob create(UUID repositoryId) {
		return jdbc.sql("INSERT INTO ingest_jobs (id, repository_id, status) VALUES (:id, :repo, 'QUEUED') RETURNING *")
			.param("id", UUID.randomUUID())
			.param("repo", repositoryId)
			.query(MAPPER)
			.single();
	}

	public Optional<IngestJob> findById(UUID id) {
		return jdbc.sql("SELECT * FROM ingest_jobs WHERE id = :id").param("id", id).query(MAPPER).optional();
	}

	public Optional<IngestJob> findLatestForRepo(UUID repositoryId) {
		return jdbc.sql("SELECT * FROM ingest_jobs WHERE repository_id = :repo ORDER BY created_at DESC LIMIT 1")
			.param("repo", repositoryId)
			.query(MAPPER)
			.optional();
	}

	/** True when the repository already has a job that has not finished. */
	public boolean hasActiveJob(UUID repositoryId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM ingest_jobs
				               WHERE repository_id = :repo AND status NOT IN ('COMPLETED', 'FAILED'))
				""")
			.param("repo", repositoryId)
			.query(Boolean.class)
			.single();
	}

	/**
	 * Atomically moves a QUEUED job to CLONING. Returns false if another consumer already claimed
	 * it, which makes processing idempotent under Kafka's at-least-once delivery.
	 */
	public boolean claim(UUID id) {
		return jdbc.sql("""
				UPDATE ingest_jobs SET status = 'CLONING', started_at = now()
				WHERE id = :id AND status = 'QUEUED'
				""").param("id", id).update() == 1;
	}

	/** QUEUED jobs older than {@code age}: their Kafka message was probably never sent. */
	public List<IngestJob> findQueuedOlderThan(Duration age) {
		return jdbc.sql("""
				SELECT * FROM ingest_jobs
				WHERE status = 'QUEUED' AND created_at < now() - make_interval(secs => :seconds)
				ORDER BY created_at
				LIMIT 100
				""").param("seconds", age.toSeconds()).query(MAPPER).list();
	}

	/** Fails running jobs whose worker died; returns the affected repository ids. */
	public List<UUID> failRunningOlderThan(Duration age) {
		return jdbc.sql("""
				UPDATE ingest_jobs
				SET status = 'FAILED', error = 'Timed out: worker stopped responding', finished_at = now()
				WHERE status IN ('CLONING', 'PARSING', 'EMBEDDING')
				  AND started_at < now() - make_interval(secs => :seconds)
				RETURNING repository_id
				""").param("seconds", age.toSeconds()).query(UUID.class).list();
	}

	public void transition(UUID id, JobStatus status) {
		boolean terminal = status.isTerminal();
		jdbc.sql("""
				UPDATE ingest_jobs
				SET status = :status,
				    started_at = COALESCE(started_at, now()),
				    finished_at = CASE WHEN :terminal THEN now() ELSE finished_at END
				WHERE id = :id
				""")
			.param("status", status.name())
			.param("terminal", terminal)
			.param("id", id)
			.update();
	}

	public void recordParseResult(UUID id, int filesTotal, int filesParsed, int chunksTotal, int linesOfCode) {
		jdbc.sql("""
				UPDATE ingest_jobs
				SET files_total = :filesTotal, files_parsed = :filesParsed,
				    chunks_total = :chunksTotal, lines_of_code = :loc
				WHERE id = :id
				""")
			.param("filesTotal", filesTotal)
			.param("filesParsed", filesParsed)
			.param("chunksTotal", chunksTotal)
			.param("loc", linesOfCode)
			.param("id", id)
			.update();
	}

	public void addEmbedded(UUID id, int count) {
		jdbc.sql("UPDATE ingest_jobs SET chunks_embedded = chunks_embedded + :count WHERE id = :id")
			.param("count", count)
			.param("id", id)
			.update();
	}

	public void fail(UUID id, String error) {
		jdbc.sql("UPDATE ingest_jobs SET status = 'FAILED', error = :error, finished_at = now() WHERE id = :id")
			.param("error", error)
			.param("id", id)
			.update();
	}

	private static IngestJob map(ResultSet rs, int row) throws SQLException {
		var started = rs.getObject("started_at", OffsetDateTime.class);
		var finished = rs.getObject("finished_at", OffsetDateTime.class);
		return new IngestJob(
				rs.getObject("id", UUID.class),
				rs.getObject("repository_id", UUID.class),
				JobStatus.valueOf(rs.getString("status")),
				rs.getInt("files_total"),
				rs.getInt("files_parsed"),
				rs.getInt("chunks_total"),
				rs.getInt("chunks_embedded"),
				rs.getInt("lines_of_code"),
				rs.getString("error"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				started == null ? null : started.toInstant(),
				finished == null ? null : finished.toInstant());
	}

}
