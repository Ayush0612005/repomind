package dev.repomind.eval;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Standard ranking metrics over a question set.
 *
 * @param recallAt1 share of questions whose first result is relevant
 * @param recallAt5 share with a relevant result in the top 5 (the context Q&A actually uses)
 * @param recallAt10 share with a relevant result in the top 10
 * @param mrr mean reciprocal rank of the first relevant result within the top 10 (0 when absent)
 * @param recallAt5ByCategory recall@5 per question category
 */
public record RetrievalScore(int questions, double recallAt1, double recallAt5, double recallAt10, double mrr,
		Map<String, Double> recallAt5ByCategory) {

	/** One question's outcome: the 1-based rank of its first relevant hit, 0 if not in the top 10. */
	public record Outcome(String questionId, String category, int rank) {

		boolean foundWithin(int k) {
			return rank > 0 && rank <= k;
		}

	}

	public static RetrievalScore of(List<Outcome> outcomes) {
		if (outcomes.isEmpty()) {
			throw new IllegalArgumentException("No outcomes to score");
		}
		Map<String, Double> byCategory = outcomes.stream()
			.collect(Collectors.groupingBy(Outcome::category, TreeMap::new,
					Collectors.averagingDouble(o -> o.foundWithin(5) ? 1 : 0)));
		return new RetrievalScore(outcomes.size(), share(outcomes, 1), share(outcomes, 5), share(outcomes, 10),
				outcomes.stream().mapToDouble(o -> o.foundWithin(10) ? 1.0 / o.rank() : 0).average().orElse(0),
				byCategory);
	}

	private static double share(List<Outcome> outcomes, int k) {
		return outcomes.stream().filter(o -> o.foundWithin(k)).count() / (double) outcomes.size();
	}

}
