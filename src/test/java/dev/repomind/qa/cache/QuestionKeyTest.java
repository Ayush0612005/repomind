package dev.repomind.qa.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class QuestionKeyTest {

	private static final UUID GENERATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void ignoresCaseSpacingAndTrailingPunctuation() {
		assertThat(QuestionKey.normalize("  How are   late fees\tCALCULATED?? ")).isEqualTo("how are late fees calculated");
		assertThat(QuestionKey.of(GENERATION, "How are late fees calculated?"))
			.isEqualTo(QuestionKey.of(GENERATION, "how are late fees calculated"));
	}

	@Test
	void keepsEverythingThatCouldChangeTheAnswer() {
		assertThat(QuestionKey.of(GENERATION, "Can a premium member borrow 10 books?"))
			.isNotEqualTo(QuestionKey.of(GENERATION, "Can a standard member borrow 10 books?"));
		// punctuation inside the question is kept: identifiers like Book#isAvailable matter
		assertThat(QuestionKey.normalize("What does Book#isAvailable() do?")).isEqualTo("what does book#isavailable() do");
	}

	@Test
	void isScopedToTheIndexGeneration() {
		assertThat(QuestionKey.of(GENERATION, "q")).isNotEqualTo(QuestionKey.of(UUID.randomUUID(), "q"))
			.hasSize(64);
	}

}
