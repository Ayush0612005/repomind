package dev.repomind.review;

import java.util.List;

/**
 * A generated review of one pull request.
 *
 * @param droppedComments comments the model produced that pointed at files or lines outside the
 * diff; they are discarded because GitHub would reject them and they are usually hallucinated
 * @param context related code from the indexed default branch that the model was shown
 */
public record Review(
		int pullRequest,
		String title,
		String headSha,
		String summary,
		List<Comment> comments,
		int droppedComments,
		List<String> reviewedFiles,
		List<SkippedFile> skippedFiles,
		List<String> context,
		String model,
		long latencyMs) {

	public enum Severity {
		BLOCKER, WARNING, NIT
	}

	/** @param line new-file line number, always one that appears in the diff */
	public record Comment(String path, int line, Severity severity, String title, String body) {
	}

	public record SkippedFile(String path, String reason) {
	}

}
