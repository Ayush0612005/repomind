package dev.repomind.review;

import java.util.List;

/** The parts of a GitHub pull request a review needs. */
public record PullRequest(int number, String title, String body, String headSha, String baseRef, String url,
		List<ChangedFile> files) {

	/** @param patch the unified diff hunks; null for binary files or diffs GitHub considers too large */
	public record ChangedFile(String path, String status, int additions, int deletions, String patch) {
	}

}
