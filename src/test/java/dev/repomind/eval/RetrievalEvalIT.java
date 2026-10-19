package dev.repomind.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.repomind.eval.EvalSet.Question;
import dev.repomind.eval.RetrievalScore.Outcome;
import dev.repomind.ingest.IngestJob;
import dev.repomind.ingest.IngestJobDao;
import dev.repomind.ingest.IngestMessaging;
import dev.repomind.ingest.JobStatus;
import dev.repomind.ingest.LocalGitRepo;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.search.HybridSearch;
import dev.repomind.search.HybridSearch.Mode;
import dev.repomind.search.SearchHit;
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
 * keyword search and fusion. The live eval measures real models.
 */
@IntegrationTest
class RetrievalEvalIT {

	private static final Logger log = LoggerFactory.getLogger(RetrievalEvalIT.class);

	private static final int TOP_K = 10;

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
		Map<String, String> fixture = fixtureFiles();
		Repo repo = ingest(fixture);
		IngestJob ingest = jobs.findById(repo.activeJobId()).orElseThrow();

		Map<Mode, RetrievalScore> scores = new EnumMap<>(Mode.class);
		Map<Mode, List<String>> misses = new EnumMap<>(Mode.class);
		for (Mode mode : Mode.values()) {
			List<Outcome> outcomes = new ArrayList<>();
			List<String> missed = new ArrayList<>();
			for (Question q : eval.questions()) {
				List<SearchHit> hits = search.search(repo, q.question(), TOP_K, mode);
				int rank = q.firstRelevantRank(hits);
				outcomes.add(new Outcome(q.id(), q.category(), rank));
				if (rank == 0 || rank > 5) {
					missed.add(miss(q, rank, hits));
				}
			}
			scores.put(mode, RetrievalScore.of(outcomes));
			misses.put(mode, missed);
		}

		String report = report(eval, fixture.size(), ingest, scores, misses);
		Path out = Path.of("target", "eval");
		Files.createDirectories(out);
		Files.writeString(out.resolve("retrieval.md"), report);
		log.info("Retrieval eval:\n{}", report);

		assertThat(eval.questions()).hasSizeGreaterThanOrEqualTo(30);
		assertThat(eval.questions()).extracting(Question::id).doesNotHaveDuplicates();
		if (eval.thresholds() != null) {
			eval.thresholds().forEach((mode, min) -> {
				RetrievalScore score = scores.get(Mode.valueOf(mode));
				assertThat(score.recallAt5()).as("%s recall@5 (baseline %.3f)", mode, min.recallAt5())
					.isGreaterThanOrEqualTo(min.recallAt5());
				assertThat(score.mrr()).as("%s MRR (baseline %.3f)", mode, min.mrr())
					.isGreaterThanOrEqualTo(min.mrr());
			});
		}
	}

	private Repo ingest(Map<String, String> files) throws Exception {
		String url = LocalGitRepo.create(temp.resolve("library"), files);
		Repo repo = repos.upsert("eval", "library-" + UUID.randomUUID(), url);
		IngestJob job = jobs.create(repo.id());
		assertThat(publisher.publish(repo.id(), job.id())).isTrue();
		IngestJob done = await().atMost(Duration.ofSeconds(120))
			.pollInterval(Duration.ofMillis(200))
			.until(() -> jobs.findById(job.id()).orElseThrow(), j -> j.status().isTerminal());
		assertThat(done.status()).as("fixture ingestion: %s", done.error()).isEqualTo(JobStatus.COMPLETED);
		return repos.findById(repo.id()).orElseThrow();
	}

	private static Map<String, String> fixtureFiles() throws IOException, URISyntaxException {
		Path root = Path.of(RetrievalEvalIT.class.getResource("/eval/library").toURI());
		Map<String, String> files = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path p : paths.filter(Files::isRegularFile).toList()) {
				files.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
			}
		}
		return files;
	}

	private static String miss(Question q, int rank, List<SearchHit> hits) {
		String got = hits.stream()
			.limit(3)
			.map(h -> h.filePath().substring(h.filePath().lastIndexOf('/') + 1) + " " + h.symbol())
			.collect(Collectors.joining("; "));
		return "| " + q.id() + " | " + (rank == 0 ? ">" + TOP_K : String.valueOf(rank)) + " | "
				+ String.join(", ", q.expect()) + " | " + got + " |";
	}

	private String report(EvalSet eval, int files, IngestJob ingest, Map<Mode, RetrievalScore> scores,
			Map<Mode, List<String>> misses) {
		StringBuilder out = new StringBuilder("# Retrieval eval\n\n");
		out.append(eval.questions().size())
			.append(" questions over the library fixture (")
			.append(files)
			.append(" files, ")
			.append(ingest.chunksTotal())
			.append(" chunks). Embedding model: `")
			.append(embeddingModel.getClass().getSimpleName())
			.append("`.\n\n");
		out.append("| Mode | Recall@1 | Recall@5 | Recall@10 | MRR@10 |\n|---|---|---|---|---|\n");
		scores.forEach((mode, s) -> out.append("| ")
			.append(mode)
			.append(" | ")
			.append(pct(s.recallAt1()))
			.append(" | ")
			.append(pct(s.recallAt5()))
			.append(" | ")
			.append(pct(s.recallAt10()))
			.append(" | ")
			.append(String.format(Locale.ROOT, "%.3f", s.mrr()))
			.append(" |\n"));

		out.append("\n## Recall@5 by category\n\n| Category |");
		scores.keySet().forEach(m -> out.append(' ').append(m).append(" |"));
		out.append("\n|---|").append("---|".repeat(scores.size())).append('\n');
		for (String category : scores.get(Mode.HYBRID).recallAt5ByCategory().keySet()) {
			out.append("| ").append(category).append(" |");
			scores.values().forEach(s -> out.append(' ').append(pct(s.recallAt5ByCategory().get(category))).append(" |"));
			out.append('\n');
		}

		out.append("\n## Hybrid misses (not in top 5)\n\n");
		List<String> hybridMisses = misses.get(Mode.HYBRID);
		if (hybridMisses.isEmpty()) {
			out.append("None.\n");
		}
		else {
			out.append("| Question | Rank | Expected | Top 3 |\n|---|---|---|---|\n");
			hybridMisses.forEach(m -> out.append(m).append('\n'));
		}
		return out.toString();
	}

	private static String pct(double value) {
		return String.format(Locale.ROOT, "%.1f%%", value * 100);
	}

}
