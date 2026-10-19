package dev.repomind.qa.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import dev.repomind.qa.Answer;
import dev.repomind.qa.Answer.Cache.Status;
import dev.repomind.qa.AnswerService;
import dev.repomind.repo.Repo;
import dev.repomind.support.IntegrationTest;
import dev.repomind.support.ScriptedChatModel;
import dev.repomind.support.TestIndexer;
import dev.repomind.support.UnreachableRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class AnswerCacheIT {

	@Autowired
	AnswerService answers;

	@Autowired
	TestIndexer indexer;

	@Autowired
	ScriptedChatModel model;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	MockMvcTester mvc;

	private Repo repo;

	@BeforeEach
	void setUp() {
		model.reset();
		repo = indexer.index("petclinic", TestIndexer.PETCLINIC);
	}

	@Test
	void exactRepeatIsServedWithoutEmbeddingOrModelCall() {
		Answer first = answers.ask(repo, "How is the vet list paged?");
		Answer second = answers.ask(repo, "  how is the VET list paged ");

		assertThat(first.cache().status()).isEqualTo(Status.MISS);
		assertThat(second.cache().status()).isEqualTo(Status.EXACT);
		assertThat(second.answer()).isEqualTo(first.answer());
		assertThat(second.citations()).isEqualTo(first.citations());
		assertThat(model.prompts()).hasSize(1);
	}

	@Test
	void rewordedQuestionWithTheSameMeaningReusesTheAnswerAndSaysSo() {
		answers.ask(repo, "How is the vet list paged?");

		// the test embedder is bag-of-words, so a reordering embeds identically
		Answer reworded = answers.ask(repo, "Paged: how is the vet list?");

		assertThat(reworded.cache().status()).isEqualTo(Status.SEMANTIC);
		assertThat(reworded.cache().matchedQuestion()).isEqualTo("How is the vet list paged?");
		assertThat(reworded.cache().similarity()).isGreaterThanOrEqualTo(0.97);
		assertThat(model.prompts()).hasSize(1);
		assertThat(jdbc.sql("SELECT hits FROM answer_cache WHERE generation_job_id = :g")
			.param("g", repo.activeJobId())
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void similarButDifferentQuestionsAreNotConflated() {
		answers.ask(repo, "How many books can a premium member borrow?");

		Answer other = answers.ask(repo, "How many books can a standard member borrow?");

		assertThat(other.cache().status()).isEqualTo(Status.MISS);
		assertThat(model.prompts()).hasSize(2);
	}

	@Test
	void reindexingInvalidatesCachedAnswers() {
		answers.ask(repo, "How is the vet list paged?");

		Repo reindexed = indexer.reindex(repo, TestIndexer.PETCLINIC);
		Answer after = answers.ask(reindexed, "How is the vet list paged?");

		assertThat(after.cache().status()).isEqualTo(Status.MISS);
		assertThat(model.prompts()).hasSize(2);
		assertThat(jdbc.sql("SELECT count(*) FROM answer_cache WHERE repository_id = :r")
			.param("r", repo.id())
			.query(Integer.class)
			.single()).as("old generation's rows are deleted on activation").isEqualTo(1);
	}

	@Test
	void failedAnswersAreNotCached() {
		model.replyWith(prompt -> {
			throw new IllegalStateException("model down");
		});
		assertThat(mvc.post()
			.uri("/api/repos/{id}/ask", repo.id())
			.contentType("application/json")
			.content("{\"question\": \"How are pets validated?\"}")).hasStatus5xxServerError();

		model.reset();
		assertThat(answers.ask(repo, "How are pets validated?").cache().status()).isEqualTo(Status.MISS);
	}

	@Test
	void redisOutageDegradesToTheSemanticTierInsteadOfFailing() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		float[] vector = new float[768];
		vector[0] = 1;
		Answer answer = new Answer("cached", List.of(), List.of(), null, 5, Answer.Cache.MISS);
		try (UnreachableRedis redis = new UnreachableRedis()) {
			AnswerCache cache = new AnswerCache(redis.template(), jdbc, json,
					new CacheProperties(true, Duration.ofHours(1), true, 0.97), meters);

			cache.store(repo, "Where is the owner repository?", vector, answer);

			assertThat(cache.exact(repo, "Where is the owner repository?")).isEmpty();
			assertThat(cache.semantic(repo, "Where is the owner repository?", vector))
				.hasValueSatisfying(a -> assertThat(a.answer()).isEqualTo("cached"));
			assertThat(meters.find("repomind.qa.cache.errors").counters()).isNotEmpty();
		}
	}

}
