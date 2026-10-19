package dev.repomind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import dev.repomind.eval.EvalSet.Question;
import dev.repomind.ingest.IngestJobDao;
import dev.repomind.ingest.IngestMessaging;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.search.HybridSearch;
import dev.repomind.search.HybridSearch.Mode;
import dev.repomind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Retrieval quality on a fixed fixture codebase: ingests it through the real pipeline (git clone,
 * AST chunking, embedding, Kafka), runs every question in each search mode, writes a report to
 * {@code target/eval/} and fails if a mode falls below its baseline in {@code questions.json}.
 *
 * <p>In CI the embedding model is the deterministic hashing fake, so the vector numbers measure
 * lexical overlap rather than a real model; the eval is a regression gate on chunking, indexing,
 * keyword search and fusion. {@link LiveEvalIT} measures real models.
 */
@IntegrationTest
class RetrievalEvalIT {

	private static final Logger log = LoggerFactory.getLogger(RetrievalEvalIT.class);

	@TempDir
	Path temp;

	@Autowired
	RepoDao repos;

	@Autowired
	IngestJobDao jobs;

	@Autowired
	IngestMessaging.Publisher publisher;

	@Autowired
	HybridSearch search;

	@Autowired
	EmbeddingModel embeddingModel;

	@Test
	void retrievalMeetsBaseline() throws Exception {
		EvalSet eval = EvalSet.load("/eval/questions.json");
		Map<String, String> fixture = EvalFixture.files();
		Repo repo = EvalFixture.ingest(temp.resolve("library"), fixture, repos, jobs, publisher, Duration.ofSeconds(120));
		int chunks = jobs.findById(repo.activeJobId()).orElseThrow().chunksTotal();

		RetrievalEval.Result result = RetrievalEval.run(search, repo, eval);

		String report = RetrievalEval.report("Retrieval eval",
				eval.questions().size() + " questions over the library fixture (" + fixture.size() + " files, " + chunks
						+ " chunks). Embedding model: `" + embeddingModel.getClass().getSimpleName() + "`.",
				result);
		Path out = Path.of("target", "eval");
		Files.createDirectories(out);
		Files.writeString(out.resolve("retrieval.md"), report);
		log.info("Retrieval eval:\n{}", report);

		assertThat(eval.questions()).hasSizeGreaterThanOrEqualTo(30);
		assertThat(eval.questions()).extracting(Question::id).doesNotHaveDuplicates();
		assertThat(eval.questions()).allSatisfy(q -> assertThat(q.facts()).as(q.id()).isNotEmpty());
		if (eval.thresholds() != null) {
			eval.thresholds().forEach((mode, min) -> {
				RetrievalScore score = result.scores().get(Mode.valueOf(mode));
				assertThat(score.recallAt5()).as("%s recall@5 (baseline %.3f)", mode, min.recallAt5())
					.isGreaterThanOrEqualTo(min.recallAt5());
				assertThat(score.mrr()).as("%s MRR (baseline %.3f)", mode, min.mrr())
					.isGreaterThanOrEqualTo(min.mrr());
			});
		}
	}

}
