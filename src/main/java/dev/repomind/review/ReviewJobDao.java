package dev.repomind.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.repomind.review.ReviewJob.Status;
import dev.repomind.review.ReviewJob.Trigger;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewJobDao {

	private static final RowMapper<ReviewJob> MAPPER = ReviewJobDao::map;

	private final JdbcClient jdbc;

	public ReviewJobDao(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Creates a QUEUED job for this commit, or returns empty when one already exists; the unique key
	 * makes this safe under concurrent webhook deliveries without a separate existence check.
	 */
	public Optional<ReviewJob> createIfAbsent(UUID repositoryId, int prNumber, String headSha, Trigger trigger) {
		return jdbc.sql("""
				INSERT INTO review_jobs (id, repository_id, pr_number, head_sha, trigger, status)
				VALUES (:id, :repo, :pr, :sha, :trigger, 'QUEUED')
				ON CONFLICT (repository_id, pr_number, head_sha) DO NOTHING
				RETURNING *
				""")
			.param("id", UUID.randomUUID())
			.param("repo", repositoryId)
			.param("pr", prNumber)
			.param("sha", headSha)
			.param("trigger", trigger.name())
			.query(MAPPER)
			.optional();
	}

	/** FAILED → QUEUED, so a commit whose review failed (e.g. GitHub was down) can be retried. */
	public Optional<ReviewJob> requeueFailed(UUID id) {
		return jdbc.sql("""
				UPDATE review_jobs
				SET status = 'QUEUED', error = NULL, started_at = NULL, finished_at = NULL, created_at = now()
				WHERE id = :id AND status = 'FAILED'
				RETURNING *
				""").param("id", id).query(MAPPER).optional();
	}

	public Optional<ReviewJob> find(UUID repositoryId, int prNumber, String headSha) {
		return jdbc.sql("""
				SELECT * FROM review_jobs WHERE repository_id = :repo AND pr_number = :pr AND head_sha = :sha
				""").param("repo", repositoryId).param("pr", prNumber).param("sha", headSha).query(MAPPER).optional();
	}

	public Optional<ReviewJob> findById(UUID id) {
		return jdbc.sql("SELECT * FROM review_jobs WHERE id = :id").param("id", id).query(MAPPER).optional();
	}

	/** QUEUED → RUNNING; false when another consumer got there first (Kafka redelivery). */
	public boolean claim(UUID id) {
		return jdbc.sql("UPDATE review_jobs SET status = 'RUNNING', started_at = now() WHERE id = :id AND status = 'QUEUED'")
			.param("id", id)
			.update() == 1;
	}

	public void markPosted(UUID id, int commentsPosted, int droppedComments, String reviewUrl) {
		jdbc.sql("""
				UPDATE review_jobs
				SET status = 'POSTED', comments_posted = :posted, dropped_comments = :dropped,
				    github_review_url = :url, finished_at = now()
				WHERE id = :id
				""")
			.param("posted", commentsPosted)
			.param("dropped", droppedComments)
			.param("url", reviewUrl)
			.param("id", id)
			.update();
	}

	public void finish(UUID id, Status status, String error) {
		jdbc.sql("UPDATE review_jobs SET status = :status, error = :error, finished_at = now() WHERE id = :id")
			.param("status", status.name())
			.param("error", error)
			.param("id", id)
			.update();
	}

	public List<ReviewJob> findQueuedOlderThan(Duration age) {
		return jdbc.sql("""
				SELECT * FROM review_jobs
				WHERE status = 'QUEUED' AND created_at < now() - make_interval(secs => :seconds)
				ORDER BY created_at LIMIT 100
				""").param("seconds", age.toSeconds()).query(MAPPER).list();
	}

	public int failRunningOlderThan(Duration age) {
		return jdbc.sql("""
				UPDATE review_jobs
				SET status = 'FAILED', error = 'Timed out: worker stopped responding', finished_at = now()
				WHERE status = 'RUNNING' AND started_at < now() - make_interval(secs => :seconds)
				""").param("seconds", age.toSeconds()).update();
	}

	private static ReviewJob map(ResultSet rs, int row) throws SQLException {
		var started = rs.getObject("started_at", OffsetDateTime.class);
		var finished = rs.getObject("finished_at", OffsetDateTime.class);
		return new ReviewJob(
				rs.getObject("id", UUID.class),
				rs.getObject("repository_id", UUID.class),
				rs.getInt("pr_number"),
				rs.getString("head_sha"),
				Trigger.valueOf(rs.getString("trigger")),
				Status.valueOf(rs.getString("status")),
				(Integer) rs.getObject("comments_posted"),
				(Integer) rs.getObject("dropped_comments"),
				rs.getString("github_review_url"),
				rs.getString("error"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				started == null ? null : started.toInstant(),
				finished == null ? null : finished.toInstant());
	}

}
