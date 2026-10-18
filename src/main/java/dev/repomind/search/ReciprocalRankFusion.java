package dev.repomind.search;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges ranked lists without needing comparable scores: an item's fused score is
 * {@code Σ 1 / (k + rank)} over the lists it appears in (rank is 1-based). Cosine distances and
 * {@code ts_rank} values live on unrelated scales, so fusing ranks is more robust than mixing raw
 * scores. {@code k = 60} is the constant from Cormack et al. (SIGIR 2009).
 */
public final class ReciprocalRankFusion {

	public static final int DEFAULT_K = 60;

	private ReciprocalRankFusion() {
	}

	/** @return ids ordered by fused score, best first; ties keep first-seen order */
	public static <T> List<Map.Entry<T, Double>> fuse(List<List<T>> rankings, int k) {
		Map<T, Double> scores = new LinkedHashMap<>();
		for (List<T> ranking : rankings) {
			for (int i = 0; i < ranking.size(); i++) {
				scores.merge(ranking.get(i), 1.0 / (k + i + 1), Double::sum);
			}
		}
		return scores.entrySet()
			.stream()
			.sorted(Map.Entry.<T, Double>comparingByValue(Comparator.reverseOrder()))
			.toList();
	}

}
