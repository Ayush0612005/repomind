package dev.repomind.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.repomind.ingest.GitHubRepoRef;
import dev.repomind.repo.Repo;
import dev.repomind.review.PullRequest;
import dev.repomind.review.PullRequestNotFoundException;
import dev.repomind.review.PullRequestSource;

/** In-memory pull requests, so review tests never call GitHub. */
public class FakePullRequests implements PullRequestSource {

	private final Map<String, PullRequest> pulls = new ConcurrentHashMap<>();

	public void put(Repo repo, PullRequest pr) {
		pulls.put(repo.owner() + "/" + repo.name() + "#" + pr.number(), pr);
	}

	@Override
	public PullRequest fetch(GitHubRepoRef repository, int number) {
		PullRequest pr = pulls.get(repository + "#" + number);
		if (pr == null) {
			throw new PullRequestNotFoundException("No pull request " + repository + "#" + number);
		}
		return pr;
	}

}
