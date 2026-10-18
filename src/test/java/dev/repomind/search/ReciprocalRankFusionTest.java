package dev.repomind.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

	@Test
	void itemsFoundByBothRetrieversOutrankSingleListWinners() {
		List<Map.Entry<String, Double>> fused = ReciprocalRankFusion
			.fuse(List.of(List.of("a", "shared", "b"), List.of("c", "shared")), 60);

		assertThat(fused).extracting(Map.Entry::getKey).containsExactly("shared", "a", "c", "b");
		assertThat(fused.getFirst().getValue()).isCloseTo(1.0 / 62 + 1.0 / 62, within(1e-12));
	}

	@Test
	void singleListKeepsItsOrderAndEmptyListsAreIgnored() {
		assertThat(ReciprocalRankFusion.fuse(List.of(List.of(3L, 1L, 2L), List.<Long>of()), 60))
			.extracting(Map.Entry::getKey)
			.containsExactly(3L, 1L, 2L);
		assertThat(ReciprocalRankFusion.fuse(List.of(List.of(), List.of()), 60)).isEmpty();
	}

}
