package dev.repomind.search;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.repomind.index.EmbeddingService;
import dev.repomind.index.Vectors;
import dev.repomind.parse.Identifiers;
import dev.repomind.repo.Repo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hybrid retrieval over a repository's active chunk generation. Dense vectors find code that
 * matches the question's meaning ("where are owners validated?"); full-text search finds exact
 * identifiers the embedding model may blur ("OwnerRepository"). Each retriever over-fetches
 * candidates and the two rankings are merged with reciprocal rank fusion.
 */
@Service
public class HybridSearch {

	public enum Mode {
		HYBRID, VECTOR, KEYWORD
	}

	/** Candidates fetched from each retriever per requested result. */
	private static final int CANDIDATE_FACTOR = 4;

	private static final int MAX_QUERY_TERMS = 32;

	private final JdbcClient jdbc;

	private final EmbeddingService embeddings;

	private final MeterRegistry meters;

	public HybridSearch(JdbcClient jdbc, EmbeddingService embeddings, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.embeddings = embeddings;
		this.meters = meters;
	}

	@Transactional(readOnly = true)
	public List<SearchHit> search(Repo repo, String query, int limit, Mode mode) {
		if (repo.activeJobId() == null) {
			throw new NotIndexedException(repo);
		}
		if (query == null || query.isBlank()) {
			throw new IllegalArgumentException("Query must not be blank");
		}
		Timer.Sample sample = Timer.start(meters);
		try {
			int candidates = limit * CANDIDATE_FACTOR;
			List<Long> vector = mode == Mode.KEYWORD ? List.of() : vectorRanking(repo.activeJobId(), query, candidates);
			List<Long> keyword = mode == Mode.VECTOR ? List.of() : keywordRanking(repo.activeJobId(), query, candidates);

			List<Map.Entry<Long, Double>> fused = ReciprocalRankFusion
				.fuse(List.of(vector, keyword), ReciprocalRankFusion.DEFAULT_K)
				.stream()
				.limit(limit)
				.toList();
			return hydrate(fused, vector, keyword);
		}
		finally {
			sample.stop(meters.timer("repomind.search.latency", "mode", mode.name().toLowerCase()));
		}
	}

	private List<Long> vectorRanking(UUID jobId, String query, int k) {
		// The HNSW index spans every repository; with a selective filter a plain index scan can stop
		// with fewer than k matches. pgvector 0.8's iterative scan keeps walking the graph until the
		// filter is satisfied. relaxed_order may return neighbours slightly out of order, so the
		// materialized CTE re-sorts them by exact distance.
		jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
		return jdbc.sql("""
				WITH nearest AS MATERIALIZED (
				    SELECT id, embedding <=> CAST(:vector AS vector) AS distance
				    FROM code_chunks
				    WHERE ingest_job_id = :job AND embedding IS NOT NULL
				    ORDER BY distance
				    LIMIT :k
				)
				SELECT id FROM nearest ORDER BY distance, id
				""")
			.param("vector", Vectors.toLiteral(embeddings.embedQuery(query)))
			.param("job", jobId)
			.param("k", k)
			.query(Long.class)
			.list();
	}

	private List<Long> keywordRanking(UUID jobId, String query, int k) {
		String tsquery = toTsQuery(query);
		if (tsquery.isEmpty()) {
			return List.of();
		}
		// cover density ranking rewards chunks where the query terms occur close together
		return jdbc.sql("""
				SELECT c.id
				FROM code_chunks c, to_tsquery('simple', :tsquery) q
				WHERE c.ingest_job_id = :job AND c.tsv @@ q
				ORDER BY ts_rank_cd(c.tsv, q) DESC, c.id
				LIMIT :k
				""")
			.param("tsquery", tsquery)
			.param("job", jobId)
			.param("k", k)
			.query(Long.class)
			.list();
	}

	/**
	 * Runs the query through the same identifier splitting used at index time, so "findOwnerById"
	 * and "find owner" both reach the stored terms, then ORs the terms: ranking, not matching, is
	 * what orders the results. Terms are reduced to {@code [a-z0-9_]}, which cannot carry tsquery
	 * operators.
	 */
	static String toTsQuery(String query) {
		return Arrays.stream(Identifiers.searchText(query).split(" "))
			.map(term -> term.replaceAll("[^a-z0-9_]", ""))
			.filter(term -> term.length() > 1)
			.distinct()
			.limit(MAX_QUERY_TERMS)
			.collect(Collectors.joining(" | "));
	}

	private List<SearchHit> hydrate(List<Map.Entry<Long, Double>> fused, List<Long> vector, List<Long> keyword) {
		if (fused.isEmpty()) {
			return List.of();
		}
		Map<Long, SearchHit> rows = new HashMap<>();
		jdbc.sql("""
				SELECT id, file_path, kind, symbol, start_line, end_line, content
				FROM code_chunks WHERE id IN (:ids)
				""")
			.param("ids", fused.stream().map(Map.Entry::getKey).toList())
			.query((rs, row) -> {
				long id = rs.getLong("id");
				return new SearchHit(id, rs.getString("file_path"), rs.getString("kind"), rs.getString("symbol"),
						rs.getInt("start_line"), rs.getInt("end_line"), rs.getString("content"), 0,
						vector.indexOf(id) + 1, keyword.indexOf(id) + 1);
			})
			.list()
			.forEach(hit -> rows.put(hit.chunkId(), hit));
		return fused.stream().filter(e -> rows.containsKey(e.getKey())).map(e -> {
			SearchHit h = rows.get(e.getKey());
			return new SearchHit(h.chunkId(), h.filePath(), h.kind(), h.symbol(), h.startLine(), h.endLine(),
					h.content(), e.getValue(), h.vectorRank(), h.keywordRank());
		}).toList();
	}

}
