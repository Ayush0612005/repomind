package dev.repomind.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.repomind.TestcontainersConfiguration;
import dev.repomind.eval.EvalSet.Question;
import dev.repomind.eval.EvalSet.Target;
import dev.repomind.eval.EvalSet.Unanswerable;
import dev.repomind.ingest.IngestJobDao;
import dev.repomind.ingest.IngestMessaging;
import dev.repomind.qa.Answer;
import dev.repomind.qa.AnswerService;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.search.HybridSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end quality with real models: retrieval with real embeddings, then every question answered
 * by the configured chat model and checked for correct citations, stated facts and, for questions
 * the code cannot answer, abstention.
 *
 * <p>Opt-in (needs Ollama, and an API key for Claude): see {@code application-live-eval.yaml}. Model
 * output varies between runs, so this reports rather than gates; {@link RetrievalEvalIT} is the CI gate.
 */
@SpringBootTest
@ActiveProfiles("live-eval")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "REPOMIND_EVAL_LIVE", matches = "true")
class LiveEvalIT {

	private static final Logger log = LoggerFactory.getLogger(LiveEvalIT.class);

	record AnswerOutcome(String id, String category, boolean cited, boolean citedExpected, int factsStated,
			int factsTotal, boolean abstained, long latencyMs, String answer, List<String> citations) {
	}

	record UnanswerableOutcome(String id, boolean abstained, int citations, long latencyMs, String answer) {
	}

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
	AnswerService answers;

	@Autowired
	EmbeddingModel embeddingModel;

	@Autowired
	ChatModel chatModel;

	@Test
	void evaluateWithRealModels() throws Exception {
		EvalSet eval = EvalSet.load("/eval/questions.json");
		Map<String, String> fixture = EvalFixture.files();
		Repo repo = EvalFixture.ingest(temp.resolve("library"), fixture, repos, jobs, publisher, Duration.ofMinutes(10));
		int chunks = jobs.findById(repo.activeJobId()).orElseThrow().chunksTotal();
		String models = "Embedding model: `" + embeddingModel.getClass().getSimpleName() + "`, chat model: `"
				+ chatModel.getClass().getSimpleName() + "` (" + chatModel.getDefaultOptions().getModel() + ").";
		Path out = Path.of("target", "eval");
		Files.createDirectories(out);

		RetrievalEval.Result retrieval = RetrievalEval.run(search, repo, eval);
		String retrievalReport = RetrievalEval.report("Retrieval eval (live models)", eval.questions().size()
				+ " questions over the library fixture (" + fixture.size() + " files, " + chunks + " chunks). " + models,
				retrieval);
		Files.writeString(out.resolve("retrieval-live.md"), retrievalReport);
		log.info("Live retrieval eval:\n{}", retrievalReport);

		List<AnswerOutcome> answered = new ArrayList<>();
		for (Question q : eval.questions()) {
			Answer a = answers.ask(repo, q.question());
			List<Target> targets = q.targets();
			boolean citedExpected = a.citations()
				.stream()
				.anyMatch(c -> targets.stream().anyMatch(t -> t.matches(pathOf(c.location()), c.symbol())));
			answered.add(new AnswerOutcome(q.id(), q.category(), !a.citations().isEmpty(), citedExpected,
					q.factsStated(a.answer()), q.facts().size(), Abstention.detect(a.answer()), a.latencyMs(),
					a.answer(), a.citations().stream().map(Answer.Source::location).toList()));
			log.info("[{}/{}] {}: cited expected={} facts={}/{}", answered.size(), eval.questions().size(), q.id(),
					citedExpected, q.factsStated(a.answer()), q.facts().size());
		}
		List<UnanswerableOutcome> declined = new ArrayList<>();
		for (Unanswerable q : eval.unanswerable()) {
			Answer a = answers.ask(repo, q.question());
			declined.add(new UnanswerableOutcome(q.id(), Abstention.detect(a.answer()), a.citations().size(),
					a.latencyMs(), a.answer()));
		}

		String answerReport = answerReport(models, answered, declined);
		Files.writeString(out.resolve("answers-live.md"), answerReport);
		log.info("Live answer eval:\n{}", answerReport.substring(0, answerReport.indexOf("## Answers")));
	}

	private static String pathOf(String location) {
		int colon = location.lastIndexOf(':');
		return colon < 0 ? location : location.substring(0, colon);
	}

	private static String answerReport(String models, List<AnswerOutcome> answered, List<UnanswerableOutcome> declined) {
		int n = answered.size();
		double citedExpected = answered.stream().filter(AnswerOutcome::citedExpected).count() / (double) n;
		double grounded = answered.stream().filter(AnswerOutcome::cited).count() / (double) n;
		double factRecall = answered.stream().mapToDouble(o -> o.factsStated() / (double) o.factsTotal()).average().orElse(0);
		double allFacts = answered.stream().filter(o -> o.factsStated() == o.factsTotal()).count() / (double) n;
		double falseAbstention = answered.stream().filter(AnswerOutcome::abstained).count() / (double) n;
		double abstention = declined.stream().filter(UnanswerableOutcome::abstained).count() / (double) declined.size();
		List<Long> latencies = new ArrayList<>(answered.stream().map(AnswerOutcome::latencyMs).toList());
		declined.forEach(d -> latencies.add(d.latencyMs()));
		latencies.sort(null);

		StringBuilder out = new StringBuilder("# Answer eval (live models)\n\n").append(models).append("\n\n");
		out.append("| Metric | Value |\n|---|---|\n")
			.append("| Answers citing the expected code | ").append(RetrievalEval.pct(citedExpected)).append(" |\n")
			.append("| Answers with at least one citation | ").append(RetrievalEval.pct(grounded)).append(" |\n")
			.append("| Fact recall (mean share of expected facts stated) | ").append(RetrievalEval.pct(factRecall)).append(" |\n")
			.append("| Answers stating every expected fact | ").append(RetrievalEval.pct(allFacts)).append(" |\n")
			.append("| Answerable questions wrongly declined | ").append(RetrievalEval.pct(falseAbstention)).append(" |\n")
			.append("| Unanswerable questions correctly declined | ").append(RetrievalEval.pct(abstention)).append(" |\n")
			.append("| Latency p50 / p95 | ")
			.append(percentile(latencies, 50)).append(" ms / ").append(percentile(latencies, 95)).append(" ms |\n");

		out.append("\n## Per question\n\n| Question | Category | Cites expected | Facts | Declined | Latency |\n|---|---|---|---|---|---|\n");
		answered.forEach(o -> out.append("| ").append(o.id()).append(" | ").append(o.category()).append(" | ")
			.append(o.citedExpected() ? "yes" : "**no**").append(" | ").append(o.factsStated()).append('/')
			.append(o.factsTotal()).append(" | ").append(o.abstained() ? "**yes**" : "no").append(" | ")
			.append(o.latencyMs()).append(" ms |\n"));
		out.append("\n| Unanswerable | Declined | Citations | Latency |\n|---|---|---|---|\n");
		declined.forEach(d -> out.append("| ").append(d.id()).append(" | ").append(d.abstained() ? "yes" : "**no**")
			.append(" | ").append(d.citations()).append(" | ").append(d.latencyMs()).append(" ms |\n"));

		out.append("\n## Answers\n");
		answered.forEach(o -> out.append("\n### ").append(o.id()).append("\n\n").append(o.answer())
			.append("\n\nCitations: ").append(o.citations().isEmpty() ? "none" : String.join(", ", o.citations()))
			.append('\n'));
		declined.forEach(d -> out.append("\n### ").append(d.id()).append(" (unanswerable)\n\n").append(d.answer())
			.append('\n'));
		return out.toString();
	}

	private static long percentile(List<Long> sorted, int p) {
		if (sorted.isEmpty()) {
			return 0;
		}
		int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
		return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
	}

}
