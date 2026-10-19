package dev.repomind.review;

import dev.repomind.ingest.GitHubRepoRef;

/** Publishes a generated review on the pull request; GitHub in production, a recorder in tests. */
public interface ReviewPublisher {

	/** @return the published review's URL */
	String publish(GitHubRepoRef repository, Review review);

}
