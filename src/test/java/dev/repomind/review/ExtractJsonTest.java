package dev.repomind.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExtractJsonTest {

	@Test
	void takesTheFencedBlockEvenWithNestedBracesAndSurroundingProse() {
		assertThat(ReviewService.extractJson("Here you go:\n```json\n{\"a\": {\"b\": 1}}\n```\nThanks!"))
			.isEqualTo("{\"a\": {\"b\": 1}}");
		assertThat(ReviewService.extractJson("```\n{\"a\": 1}\n```")).isEqualTo("{\"a\": 1}");
	}

	@Test
	void fallsBackToOutermostBracesThenToTheRawText() {
		assertThat(ReviewService.extractJson("Sure! {\"summary\": \"ok\", \"comments\": []} Hope that helps."))
			.isEqualTo("{\"summary\": \"ok\", \"comments\": []}");
		assertThat(ReviewService.extractJson("Looks good to me!")).isEqualTo("Looks good to me!");
	}

}
