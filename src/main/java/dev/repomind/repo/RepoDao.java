package dev.repomind.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepoDao {

	private static final RowMapper<Repo> MAPPER = RepoDao::map;

	private final JdbcClient jdbc;

	public RepoDao(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Inserts the repository, or returns the existing row when owner/name is already tracked.
	 */
	public Repo upsert(String owner, String name, String cloneUrl) {
		return jdbc.sql("""
				INSERT INTO repositories (id, owner, name, clone_url, status)
				VALUES (:id, :owner, :name, :cloneUrl, 'PENDING')
				ON CONFLICT (owner, name) DO UPDATE SET clone_url = EXCLUDED.clone_url, updated_at = now()
				RETURNING *
				""")
			.param("id", UUID.randomUUID())
			.param("owner", owner)
			.param("name", name)
			.param("cloneUrl", cloneUrl)
			.query(MAPPER)
			.single();
	}

	public Optional<Repo> findById(UUID id) {
		return jdbc.sql("SELECT * FROM repositories WHERE id = :id").param("id", id).query(MAPPER).optional();
	}

	public Optional<Repo> findByFullName(String owner, String name) {
		return jdbc.sql("SELECT * FROM repositories WHERE lower(owner) = lower(:owner) AND lower(name) = lower(:name)")
			.param("owner", owner)
			.param("name", name)
			.query(MAPPER)
			.optional();
	}

	public List<Repo> findAll() {
		return jdbc.sql("SELECT * FROM repositories ORDER BY created_at DESC").query(MAPPER).list();
	}

	public void updateStatus(UUID id, RepoStatus status) {
		jdbc.sql("UPDATE repositories SET status = :status, updated_at = now() WHERE id = :id")
			.param("status", status.name())
			.param("id", id)
			.update();
	}

	public void markReady(UUID id, String defaultBranch, String commitSha) {
		jdbc.sql("""
				UPDATE repositories
				SET status = 'READY', default_branch = :branch, commit_sha = :sha, updated_at = now()
				WHERE id = :id
				""")
			.param("branch", defaultBranch)
			.param("sha", commitSha)
			.param("id", id)
			.update();
	}

	private static Repo map(ResultSet rs, int row) throws SQLException {
		return new Repo(
				rs.getObject("id", UUID.class),
				rs.getString("owner"),
				rs.getString("name"),
				rs.getString("clone_url"),
				rs.getString("default_branch"),
				rs.getString("commit_sha"),
				RepoStatus.valueOf(rs.getString("status")),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

}
