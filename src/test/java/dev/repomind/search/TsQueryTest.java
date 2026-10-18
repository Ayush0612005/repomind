package dev.repomind.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TsQueryTest {

	@Test
	void splitsIdentifiersAndOrsTheTerms() {
		assertThat(HybridSearch.toTsQuery("where is findOwnerById called?"))
			.isEqualTo("where | findownerbyid | find | owner | by | id | called");
	}

	@Test
	void dropsOperatorsAndNoise() {
		assertThat(HybridSearch.toTsQuery("owner & !pet | (vet):* <-> 'x'")).isEqualTo("owner | pet | vet");
		assertThat(HybridSearch.toTsQuery("the public static void")).isEmpty();
		assertThat(HybridSearch.toTsQuery("???")).isEmpty();
	}

}
