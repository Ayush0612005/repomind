package dev.repomind.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import dev.repomind.ingest.GitHubRepoRef;
import dev.repomind.repo.Repo;
import dev.repomind.review.GitHubUnavailableException;
import dev.repomind.review.PullRequest;
import dev.repomind.review.PullRequestNotFoundException;
import dev.repomind.review.PullRequestSource;
import dev.repomind.review.Review;
import dev.repomind.review.ReviewPublisher;

/** In-memory GitHub: serves pull requests and records published reviews, so tests never call GitHub. */
public class FakePullRequests implements PullRequestSource, ReviewPublisher {

	public record Published(GitHubRepoRef repository, Review review) {
	}

	private final Map<String, PullRequest> pulls = new ConcurrentHashMap<>();

	private final List<Published> published = new CopyOnWriteArrayList<>();

	private final AtomicReference<String> publishFailure = new AtomicReference<>();

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

	@Override
	public String publish(GitHubRepoRef repository, Review review) {
		String failure = publishFailure.getAndSet(null);
		if (failure != null) {
			throw new GitHubUnavailableException(failure, null);
		}
		published.add(new Published(repository, review));
		return "https://github.com/" + repository + "/pull/" + review.pullRequest() + "#pullrequestreview-"
				+ published.size();
	}

	/** Makes the next publish fail with this message. */
	public void failNextPublish(String message) {
		publishFailure.set(message);
	}

	public List<Published> publishedTo(Repo repo) {
		return published.stream()
			.filter(p -> p.repository().owner().equals(repo.owner()) && p.repository().name().equals(repo.name()))
			.toList();
	}

}
