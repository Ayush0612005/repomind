package dev.repomind.review;

import dev.repomind.ingest.GitHubRepoRef;

/** Where pull requests come from; GitHub in production, an in-memory fake in tests. */
public interface PullRequestSource {

	/**
	 * @throws PullRequestNotFoundException if the repository or pull request does not exist
	 * @throws GitHubUnavailableException if GitHub cannot be reached or refuses the request
	 */
	PullRequest fetch(GitHubRepoRef repository, int number);

}
