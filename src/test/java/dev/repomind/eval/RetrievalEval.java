package dev.repomind.eval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import dev.repomind.eval.EvalSet.Question;
import dev.repomind.eval.RetrievalScore.Outcome;
import dev.repomind.repo.Repo;
import dev.repomind.search.HybridSearch;
import dev.repomind.search.HybridSearch.Mode;
import dev.repomind.search.SearchHit;

/** Runs every question in every search mode and renders the scores as Markdown. */
final class RetrievalEval {

	static final int TOP_K = 10;

	record Result(Map<Mode, RetrievalScore> scores, Map<Mode, List<String>> misses) {
	}

	private RetrievalEval() {
	}

	static Result run(HybridSearch search, Repo repo, EvalSet eval) {
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
		return new Result(scores, misses);
	}

	static String report(String title, String setup, Result result) {
		Map<Mode, RetrievalScore> scores = result.scores();
		StringBuilder out = new StringBuilder("# ").append(title).append("\n\n").append(setup).append("\n\n");
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
		List<String> hybridMisses = result.misses().get(Mode.HYBRID);
		if (hybridMisses.isEmpty()) {
			out.append("None.\n");
		}
		else {
			out.append("| Question | Rank | Expected | Top 3 |\n|---|---|---|---|\n");
			hybridMisses.forEach(m -> out.append(m).append('\n'));
		}
		return out.toString();
	}

	static String pct(double value) {
		return String.format(Locale.ROOT, "%.1f%%", value * 100);
	}

	private static String miss(Question q, int rank, List<SearchHit> hits) {
		String got = hits.stream()
			.limit(3)
			.map(h -> h.filePath().substring(h.filePath().lastIndexOf('/') + 1) + " " + h.symbol())
			.collect(Collectors.joining("; "));
		return "| " + q.id() + " | " + (rank == 0 ? ">" + TOP_K : String.valueOf(rank)) + " | "
				+ String.join(", ", q.expect()) + " | " + got + " |";
	}

}
