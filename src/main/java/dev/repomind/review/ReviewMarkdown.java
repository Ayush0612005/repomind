package dev.repomind.review;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import dev.repomind.review.Review.Comment;
import dev.repomind.review.Review.Severity;

/** Renders a review as GitHub-flavoured Markdown. */
public final class ReviewMarkdown {

	private ReviewMarkdown() {
	}

	public static String body(Review review) {
		StringBuilder out = new StringBuilder("### RepoMind review\n\n");
		out.append(review.summary().isBlank() ? "No summary was produced." : review.summary()).append("\n\n");

		if (review.comments().isEmpty()) {
			out.append("No issues found in the reviewed changes.\n\n");
		}
		else {
			Map<Severity, Long> counts = new TreeMap<>(
					review.comments().stream().collect(Collectors.groupingBy(Comment::severity, Collectors.counting())));
			out.append(counts.entrySet()
				.stream()
				.map(e -> e.getValue() + " " + e.getKey().name().toLowerCase(Locale.ROOT)
						+ (e.getValue() == 1 ? "" : "s"))
				.collect(Collectors.joining(" · ", "**", "** in the inline comments.\n\n")));
		}

		if (!review.skippedFiles().isEmpty()) {
			out.append("<details><summary>Not reviewed (")
				.append(review.skippedFiles().size())
				.append(review.skippedFiles().size() == 1 ? " file" : " files")
				.append(")</summary>\n\n");
			review.skippedFiles()
				.forEach(f -> out.append("- `").append(f.path()).append("`: ").append(f.reason()).append('\n'));
			out.append("\n</details>\n\n");
		}

		out.append("<sub>Automated review of ")
			.append(shortSha(review.headSha()))
			.append(", with context from the indexed default branch. Treat it as a second opinion.</sub>");
		return out.toString();
	}

	public static String comment(Comment comment) {
		return "**" + label(comment.severity()) + ": " + comment.title() + "**\n\n" + comment.body();
	}

	private static String label(Severity severity) {
		return switch (severity) {
			case BLOCKER -> "Blocker";
			case WARNING -> "Warning";
			case NIT -> "Nit";
		};
	}

	private static String shortSha(String sha) {
		return sha == null || sha.length() < 7 ? String.valueOf(sha) : sha.substring(0, 7);
	}

}
