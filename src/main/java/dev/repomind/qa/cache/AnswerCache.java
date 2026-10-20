package dev.repomind.qa.cache;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import dev.repomind.index.Vectors;
import dev.repomind.qa.Answer;
import dev.repomind.repo.Repo;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two-tier answer cache, both tiers scoped to the repository's active index generation:
 * <ol>
 * <li>exact: Redis, keyed by the normalised question, checked before anything is embedded;</li>
 * <li>semantic: pgvector nearest neighbour over earlier questions' embeddings, above a
 * deliberately high similarity threshold.</li>
 * </ol>
 * Either tier failing degrades to a miss; the cache never makes a question fail.
 */
@Component
public class AnswerCache {

	private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);

	private static final String KEY_PREFIX = "repomind:answer:";

	private final StringRedisTemplate redis;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final CacheProperties properties;

	private final MeterRegistry meters;

	public AnswerCache(StringRedisTemplate redis, JdbcClient jdbc, JsonMapper json, CacheProperties properties,
			MeterRegistry meters) {
		this.redis = redis;
		this.jdbc = jdbc;
		this.json = json;
		this.properties = properties;
		this.meters = meters;
	}

	public boolean enabled() {
		return properties.enabled();
	}

	/** Exact tier: the stored answer for this question in the active generation, if any. */
	public Optional<Answer> exact(Repo repo, String question) {
		if (!properties.enabled()) {
			return Optional.empty();
		}
		String key = KEY_PREFIX + QuestionKey.of(repo.activeJobId(), question);
		String stored = guarded("exact lookup", () -> redis.opsForValue().get(key));
		if (stored == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(read(stored))
			.map(a -> withCache(a, new Answer.Cache(Answer.Cache.Status.EXACT, null, 1.0)));
	}

	/** Semantic tier: the answer to the most similar earlier question, if similar enough. */
	@Transactional
	public Optional<Answer> semantic(Repo repo, String question, float[] queryVector) {
		if (!properties.enabled() || !properties.semanticEnabled()) {
			return Optional.empty();
		}
		record Nearest(long id, String question, String answer, double similarity) {
		}
		Nearest nearest = guarded("semantic lookup", () -> {
			// as for code search: keep walking the HNSW graph until the generation filter is satisfied
			jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
			return jdbc.sql("""
					SELECT id, question, answer::text AS answer,
					       1 - (embedding <=> CAST(:vector AS vector)) AS similarity
					FROM answer_cache
					WHERE generation_job_id = :generation
					  AND created_at > now() - make_interval(secs => :ttl)
					ORDER BY embedding <=> CAST(:vector AS vector)
					LIMIT 1
					""")
				.param("vector", Vectors.toLiteral(queryVector))
				.param("generation", repo.activeJobId())
				.param("ttl", properties.ttl().toSeconds())
				.query((rs, row) -> new Nearest(rs.getLong("id"), rs.getString("question"), rs.getString("answer"),
						rs.getDouble("similarity")))
				.optional()
				.orElse(null);
		});
		if (nearest == null || nearest.similarity() < properties.semanticThreshold()) {
			return Optional.empty();
		}
		guarded("hit count", () -> jdbc.sql("UPDATE answer_cache SET hits = hits + 1, last_hit_at = now() WHERE id = :id")
			.param("id", nearest.id())
			.update());
		if (QuestionKey.normalize(nearest.question()).equals(QuestionKey.normalize(question))) {
			// the same question, found here because Redis lost it (restart, eviction): report it as the
			// exact hit it is and put it back in the fast tier
			String key = KEY_PREFIX + QuestionKey.of(repo.activeJobId(), question);
			guarded("exact refill", () -> {
				redis.opsForValue().set(key, nearest.answer(), properties.ttl());
				return null;
			});
			return Optional.ofNullable(read(nearest.answer()))
				.map(a -> withCache(a, new Answer.Cache(Answer.Cache.Status.EXACT, null, 1.0)));
		}
		return Optional.ofNullable(read(nearest.answer()))
			.map(a -> withCache(a, new Answer.Cache(Answer.Cache.Status.SEMANTIC, nearest.question(),
					Math.min(1.0, nearest.similarity()))));
	}

	/** Stores a freshly generated answer in both tiers. */
	public void store(Repo repo, String question, float[] queryVector, Answer answer) {
		if (!properties.enabled()) {
			return;
		}
		UUID generation = repo.activeJobId();
		String key = QuestionKey.of(generation, question);
		String value = json.writeValueAsString(withCache(answer, Answer.Cache.MISS));
		guarded("exact store", () -> {
			redis.opsForValue().set(KEY_PREFIX + key, value, properties.ttl());
			return null;
		});
		guarded("semantic store", () -> jdbc.sql("""
				INSERT INTO answer_cache (repository_id, generation_job_id, question, question_key, embedding, answer)
				VALUES (:repo, :generation, :question, :key, CAST(:vector AS vector), CAST(:answer AS jsonb))
				ON CONFLICT (generation_job_id, question_key) DO NOTHING
				""")
			.param("repo", repo.id())
			.param("generation", generation)
			.param("question", question.strip())
			.param("key", key)
			.param("vector", Vectors.toLiteral(queryVector))
			.param("answer", value)
			.update());
	}

	private Answer read(String stored) {
		try {
			return json.readValue(stored, Answer.class);
		}
		catch (RuntimeException ex) {
			// e.g. written by an older version with a different shape: treat as a miss
			log.debug("Ignoring unreadable cached answer: {}", ex.getMessage());
			return null;
		}
	}

	private static Answer withCache(Answer a, Answer.Cache cache) {
		return new Answer(a.answer(), a.citations(), a.sources(), a.model(), a.latencyMs(), cache);
	}

	private <T> T guarded(String operation, Supplier<T> action) {
		try {
			return action.get();
		}
		catch (RuntimeException ex) {
			meters.counter("repomind.qa.cache.errors", "operation", operation).increment();
			log.warn("Answer cache {} failed, continuing without it: {}", operation, ex.getMessage());
			return null;
		}
	}

}
