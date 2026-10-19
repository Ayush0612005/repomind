package dev.repomind.review;

import java.time.Instant;
import java.util.UUID;

/** One request to review a pull request at a specific commit and post the result to GitHub. */
public record ReviewJob(
		UUID id,
		UUID repositoryId,
		int prNumber,
		String headSha,
		Trigger trigger,
		Status status,
		Integer commentsPosted,
		Integer droppedComments,
		String githubReviewUrl,
		String error,
		Instant createdAt,
		Instant startedAt,
		Instant finishedAt) {

	public enum Trigger {
		API, WEBHOOK
	}

	public enum Status {
		QUEUED, RUNNING, POSTED,
		/** The pull request moved to a newer commit before the review ran; that commit gets its own job. */
		SUPERSEDED, FAILED;

		public boolean isTerminal() {
			return this != QUEUED && this != RUNNING;
		}
	}

}
