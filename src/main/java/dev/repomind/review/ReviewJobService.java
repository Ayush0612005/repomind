package dev.repomind.review;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import dev.repomind.ingest.GitHubRepoRef;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.review.ReviewJob.Status;
import dev.repomind.review.ReviewJob.Trigger;
import dev.repomind.search.NotIndexedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Queues reviews of a pull request at a given commit and runs them: review → check commit → publish. */
@Service
public class ReviewJobService {

	private static final Logger log = LoggerFactory.getLogger(ReviewJobService.class);

	private static final Duration STALE_QUEUED = Duration.ofMinutes(2);

	private static final Duration STUCK_RUNNING = Duration.ofMinutes(15);

	/** @param created false when this commit already had a job, which is returned instead */
	public record Submission(ReviewJob job, boolean created) {
	}

	private final ReviewJobDao jobs;

	private final RepoDao repos;

	private final ReviewService reviews;

	private final PullRequestSource pullRequests;

	private final ReviewPublisher publisher;

	private final ReviewMessaging.Publisher queue;

	private final MeterRegistry meters;

	public ReviewJobService(ReviewJobDao jobs, RepoDao repos, ReviewService reviews, PullRequestSource pullRequests,
			ReviewPublisher publisher, ReviewMessaging.Publisher queue, MeterRegistry meters) {
		this.jobs = jobs;
		this.repos = repos;
		this.reviews = reviews;
		this.pullRequests = pullRequests;
		this.publisher = publisher;
		this.queue = queue;
		this.meters = meters;
	}

	/** Queues a review of the pull request's current head commit. */
	public Submission submitLatest(Repo repo, int number, Trigger trigger) {
		requireIndexed(repo);
		String headSha = pullRequests.fetch(ref(repo), number).headSha();
		return submit(repo, number, headSha, trigger);
	}

	/**
	 * Queues a review of {@code headSha}. Each commit is reviewed at most once: a second request
	 * returns the existing job, except that a FAILED job is retried.
	 */
	public Submission submit(Repo repo, int number, String headSha, Trigger trigger) {
		requireIndexed(repo);
		Optional<ReviewJob> created = jobs.createIfAbsent(repo.id(), number, headSha, trigger);
		if (created.isPresent()) {
			queue.publish(repo.id(), created.get().id());
			return new Submission(created.get(), true);
		}
		ReviewJob existing = jobs.find(repo.id(), number, headSha).orElseThrow();
		if (existing.status() == Status.FAILED) {
			Optional<ReviewJob> retried = jobs.requeueFailed(existing.id());
			if (retried.isPresent()) {
				queue.publish(repo.id(), existing.id());
				return new Submission(retried.get(), true);
			}
		}
		return new Submission(existing, false);
	}

	/** Runs a queued job; a no-op when the job was already claimed (redelivered message). */
	public void run(UUID jobId) {
		if (!jobs.claim(jobId)) {
			log.info("Review job {} already claimed or finished, skipping", jobId);
			return;
		}
		ReviewJob job = jobs.findById(jobId).orElseThrow();
		Repo repo = repos.findById(job.repositoryId()).orElseThrow();
		try {
			Review review = reviews.review(repo, job.prNumber());
			if (!job.headSha().equals(review.headSha())) {
				// a newer push arrived while this job waited; its own job will review that commit
				jobs.finish(jobId, Status.SUPERSEDED, "Pull request moved to " + review.headSha());
				meters.counter("repomind.review.jobs", "outcome", "superseded").increment();
				return;
			}
			String url = publisher.publish(ref(repo), review);
			jobs.markPosted(jobId, review.comments().size(), review.droppedComments(), url);
			meters.counter("repomind.review.jobs", "outcome", "posted").increment();
			log.info("Posted review of {}#{} at {} with {} comments", repo.fullName(), job.prNumber(),
					job.headSha(), review.comments().size());
		}
		catch (RuntimeException ex) {
			log.warn("Review job {} for {}#{} failed: {}", jobId, repo.fullName(), job.prNumber(), ex.getMessage());
			jobs.finish(jobId, Status.FAILED, summarize(ex));
			meters.counter("repomind.review.jobs", "outcome", "failed").increment();
		}
	}

	/** Re-publishes jobs whose message was lost and fails jobs whose worker died, as for ingestion. */
	@Scheduled(fixedDelayString = "${repomind.review.sweep-interval:60s}", initialDelayString = "45s")
	public void sweep() {
		for (ReviewJob stale : jobs.findQueuedOlderThan(STALE_QUEUED)) {
			log.info("Re-publishing stale review job {}", stale.id());
			queue.publish(stale.repositoryId(), stale.id());
		}
		int stuck = jobs.failRunningOlderThan(STUCK_RUNNING);
		if (stuck > 0) {
			log.warn("Failed {} review jobs that stopped making progress", stuck);
		}
	}

	private static void requireIndexed(Repo repo) {
		if (repo.activeJobId() == null) {
			throw new NotIndexedException(repo);
		}
	}

	private static GitHubRepoRef ref(Repo repo) {
		return new GitHubRepoRef(repo.owner(), repo.name());
	}

	private static String summarize(Throwable ex) {
		String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
		return message.length() > 1_000 ? message.substring(0, 1_000) + "…" : message;
	}

}
