package dev.repomind.index;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import dev.repomind.parse.CodeChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Writes chunk generations and their embeddings; see V2 migration for the generation model. */
@Repository
public class ChunkStore {

	/** A chunk awaiting its embedding, with the exact text that will be embedded. */
	public record PendingChunk(long id, String text) {
	}

	private final JdbcClient jdbc;

	private final JdbcTemplate template;

	public ChunkStore(JdbcClient jdbc, JdbcTemplate template) {
		this.jdbc = jdbc;
		this.template = template;
	}

	public void insert(UUID repositoryId, UUID jobId, List<CodeChunk> chunks) {
		template.batchUpdate("""
				INSERT INTO code_chunks (repository_id, ingest_job_id, file_path, kind, package_name, type_name,
				                         symbol, start_line, end_line, content, content_hash, search_text, token_count)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", chunks, 500, (PreparedStatement ps, CodeChunk c) -> {
			ps.setObject(1, repositoryId);
			ps.setObject(2, jobId);
			ps.setString(3, c.filePath());
			ps.setString(4, c.kind().name());
			ps.setString(5, c.packageName());
			ps.setString(6, c.typeName());
			ps.setString(7, c.symbol());
			ps.setInt(8, c.startLine());
			ps.setInt(9, c.endLine());
			ps.setString(10, stripNul(c.content()));
			ps.setString(11, c.contentHash());
			ps.setString(12, stripNul(c.searchText()));
			ps.setInt(13, c.tokenCount());
		});
	}

	/**
	 * Copies embeddings from the active generation for chunks whose embedded text is unchanged
	 * (same path, symbol and content hash), so re-indexing only pays for what changed.
	 * @return number of chunks that reused an embedding
	 */
	public int reuseEmbeddings(UUID repositoryId, UUID jobId) {
		return jdbc.sql("""
				UPDATE code_chunks n
				SET embedding = o.embedding
				FROM code_chunks o
				JOIN repositories r ON r.id = o.repository_id AND o.ingest_job_id = r.active_job_id
				WHERE n.ingest_job_id = :job
				  AND o.repository_id = :repo
				  AND o.embedding IS NOT NULL
				  AND o.content_hash = n.content_hash
				  AND o.file_path = n.file_path
				  AND o.symbol = n.symbol
				""")
			.param("job", jobId)
			.param("repo", repositoryId)
			.update();
	}

	public List<PendingChunk> findUnembedded(UUID jobId, int limit) {
		return jdbc.sql("""
				SELECT id, file_path, kind, symbol, content
				FROM code_chunks
				WHERE ingest_job_id = :job AND embedding IS NULL
				ORDER BY id
				LIMIT :limit
				""")
			.param("job", jobId)
			.param("limit", limit)
			.query((rs, row) -> new PendingChunk(rs.getLong("id"),
					EmbeddingText.of(rs.getString("file_path"), rs.getString("kind"), rs.getString("symbol"),
							rs.getString("content"))))
			.list();
	}

	public void saveEmbeddings(List<Long> ids, List<float[]> vectors) {
		if (ids.size() != vectors.size()) {
			throw new IllegalArgumentException("ids and vectors differ in size");
		}
		template.batchUpdate("UPDATE code_chunks SET embedding = CAST(? AS vector) WHERE id = ?",
				new org.springframework.jdbc.core.BatchPreparedStatementSetter() {

					@Override
					public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
						ps.setString(1, Vectors.toLiteral(vectors.get(i)));
						ps.setLong(2, ids.get(i));
					}

					@Override
					public int getBatchSize() {
						return ids.size();
					}

				});
	}

	/** Makes {@code jobId} the generation served to queries and drops every other generation. */
	@Transactional
	public void activate(UUID repositoryId, UUID jobId) {
		jdbc.sql("UPDATE repositories SET active_job_id = :job, updated_at = now() WHERE id = :repo")
			.param("job", jobId)
			.param("repo", repositoryId)
			.update();
		jdbc.sql("DELETE FROM code_chunks WHERE repository_id = :repo AND ingest_job_id <> :job")
			.param("repo", repositoryId)
			.param("job", jobId)
			.update();
	}

	public void deleteGeneration(UUID jobId) {
		jdbc.sql("DELETE FROM code_chunks WHERE ingest_job_id = :job").param("job", jobId).update();
	}

	public int countGeneration(UUID jobId) {
		return jdbc.sql("SELECT count(*) FROM code_chunks WHERE ingest_job_id = :job")
			.param("job", jobId)
			.query(Integer.class)
			.single();
	}

	/** Postgres text columns cannot hold U+0000, which occasionally appears in source string literals. */
	private static String stripNul(String text) {
		return text.indexOf('\0') < 0 ? text : text.replace("\0", "");
	}

}
