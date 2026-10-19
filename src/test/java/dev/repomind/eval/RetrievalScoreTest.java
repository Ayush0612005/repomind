package dev.repomind.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import dev.repomind.eval.EvalSet.Question;
import dev.repomind.eval.EvalSet.Target;
import dev.repomind.eval.RetrievalScore.Outcome;
import dev.repomind.search.SearchHit;
import org.junit.jupiter.api.Test;

class RetrievalScoreTest {

	@Test
	void computesRecallAtKAndMeanReciprocalRank() {
		RetrievalScore score = RetrievalScore.of(List.of(new Outcome("a", "x", 1), new Outcome("b", "x", 3),
				new Outcome("c", "y", 7), new Outcome("d", "y", 0)));

		assertThat(score.recallAt1()).isEqualTo(0.25);
		assertThat(score.recallAt5()).isEqualTo(0.5);
		assertThat(score.recallAt10()).isEqualTo(0.75);
		assertThat(score.mrr()).isCloseTo((1 + 1.0 / 3 + 1.0 / 7) / 4, within(1e-9));
		assertThat(score.recallAt5ByCategory()).containsEntry("x", 1.0).containsEntry("y", 0.0);
	}

	@Test
	void targetsMatchFilesMethodsAndNestedTypes() {
		Target method = new Target("LoanService.java", "checkout");
		assertThat(method.matches("src/main/java/org/acme/loan/LoanService.java", "LoanService#checkout(UUID, String)"))
			.isTrue();
		assertThat(method.matches("src/main/java/org/acme/loan/LoanService.java", "LoanService#checkoutAll()"))
			.isFalse();
		assertThat(method.matches("src/main/java/org/acme/loan/OtherLoanService.java",
				"OtherLoanService#checkout(UUID, String)"))
			.isFalse();

		Target nested = new Target("LoanExceptions.java", "BookUnavailableException");
		assertThat(nested.matches("a/LoanExceptions.java", "org.acme.loan.LoanExceptions.BookUnavailableException"))
			.isTrue();

		assertThat(new Target("Book.java", null).matches("a/Book.java", "anything")).isTrue();
	}

	@Test
	void ranksTheFirstHitMatchingAnyExpectedTarget() {
		Question q = new Question("q", "c", "?", List.of("A.java#a", "B.java"), List.of(List.of("978", "isbn-13"), List.of("979")));
		List<SearchHit> hits = List.of(hit("C.java", "C#c()"), hit("B.java", "B#b()"), hit("A.java", "A#a()"));

		assertThat(q.firstRelevantRank(hits)).isEqualTo(2);
		assertThat(q.firstRelevantRank(List.of(hit("C.java", "C#c()")))).isZero();
	}

	private static SearchHit hit(String file, String symbol) {
		return new SearchHit(1, "src/" + file, "METHOD", symbol, 1, 2, "", 0, 0, 0);
	}

}
