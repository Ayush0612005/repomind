package dev.repomind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.repomind.eval.EvalSet.Question;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnswerChecksTest {

	@ParameterizedTest
	@ValueSource(strings = { "The provided sources do not contain any Stripe integration.",
			"Payments aren't mentioned in the code.", "There is no OAuth2 configuration in these sources.",
			"I couldn't find anything about GraphQL.", "None of the sources describe S3 uploads.",
			"The code doesn’t show how passwords are hashed.", "No information about Goodreads is available.",
			"I'm unable to determine which migration tool is used." })
	void recognisesDeclinedAnswers(String answer) {
		assertThat(Abstention.detect(answer)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Fines are 0.25 per day after the grace period, capped at 10.00 [1].",
			"A suspended member cannot borrow: checkout throws MemberSuspendedException [2].",
			"Returning a book twice throws IllegalStateException because the loan is not active [1]." })
	void doesNotFlagOrdinaryAnswersThatUseNegation(String answer) {
		assertThat(Abstention.detect(answer)).isFalse();
	}

	@Test
	void countsFactsMatchingAnyAcceptedSpellingIgnoringCase() {
		Question q = new Question("q", "c", "?", List.of("A.java"),
				List.of(List.of("HALF_UP", "half up"), List.of("2 decimal", "two decimal"), List.of("Europe/London")));

		assertThat(q.factsStated("Amounts are rounded half up to two decimal places.")).isEqualTo(2);
		assertThat(q.factsStated("Rounded with RoundingMode.HALF_UP.")).isEqualTo(1);
		assertThat(q.factsStated("No idea.")).isZero();
	}

}
