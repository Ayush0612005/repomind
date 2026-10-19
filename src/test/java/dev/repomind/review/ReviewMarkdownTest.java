package dev.repomind.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dev.repomind.review.Review.Comment;
import dev.repomind.review.Review.Severity;
import dev.repomind.review.Review.SkippedFile;
import org.junit.jupiter.api.Test;

class ReviewMarkdownTest {

	private static Review review(List<Comment> comments, List<SkippedFile> skipped) {
		return new Review(7, "t", "0123456789abcdef", "Off-by-one in paging.", comments, 0, List.of("A.java"), skipped,
				List.of(), null, 0);
	}

	@Test
	void summarisesSeverityCountsSkippedFilesAndTheCommit() {
		String body = ReviewMarkdown.body(review(
				List.of(new Comment("A.java", 3, Severity.WARNING, "w1", "b"),
						new Comment("A.java", 1, Severity.BLOCKER, "b1", "b"),
						new Comment("A.java", 5, Severity.WARNING, "w2", "b")),
				List.of(new SkippedFile("README.md", "not a Java source file"))));

		assertThat(body).startsWith("### RepoMind review\n\nOff-by-one in paging.")
			.contains("**1 blocker · 2 warnings** in the inline comments.")
			.contains("<summary>Not reviewed (1 file)</summary>", "- `README.md`: not a Java source file")
			.contains("Automated review of 0123456");
	}

	@Test
	void saysSoWhenNothingWasFound() {
		assertThat(ReviewMarkdown.body(review(List.of(), List.of()))).contains("No issues found")
			.doesNotContain("<details>");
	}

	@Test
	void labelsInlineComments() {
		assertThat(ReviewMarkdown.comment(new Comment("A.java", 1, Severity.NIT, "Name", "Use `count`.")))
			.isEqualTo("**Nit: Name**\n\nUse `count`.");
	}

}
