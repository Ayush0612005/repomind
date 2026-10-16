package dev.repomind.ingest;

import java.util.List;
import java.util.UUID;

import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.repo.RepoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Accepts ingestion requests and keeps the job queue healthy. */
@Service
public class IngestService {

	private static final Logger log = LoggerFactory.getLogger(IngestService.class);

	public record Submission(Repo repository, IngestJob job) {
	}

	/** Thrown when the repository already has an unfinished job. */
	public static class JobAlreadyRunningException extends RuntimeException {

		private final IngestJob job;

		JobAlreadyRunningException(Repo repo, IngestJob job) {
			super(repo.fullName() + " is already being ingested (job " + job.id() + ")");
			this.job = job;
		}

		public IngestJob job() {
			return job;
		}

	}

	private final RepoDao repos;

	private final IngestJobDao jobs;

	private final IngestMessaging.Publisher publisher;

	private final IngestProperties properties;

	public IngestService(RepoDao repos, IngestJobDao jobs, IngestMessaging.Publisher publisher,
			IngestProperties properties) {
		this.repos = repos;
		this.jobs = jobs;
		this.publisher = publisher;
		this.properties = properties;
	}

	public Submission submit(String repository) {
		GitHubRepoRef ref = GitHubRepoRef.parse(repository);
		Repo repo = repos.findByFullName(ref.owner(), ref.name())
			.orElseGet(() -> repos.upsert(ref.owner(), ref.name(), ref.cloneUrl()));
		return enqueue(repo);
	}

	public Submission reindex(Repo repo) {
		return enqueue(repo);
	}

	private Submission enqueue(Repo repo) {
		IngestJob job;
		try {
			job = jobs.create(repo.id());
		}
		catch (DuplicateKeyException ex) {
			// the partial unique index allows one unfinished job per repository
			throw new JobAlreadyRunningException(repo, jobs.findLatestForRepo(repo.id()).orElseThrow());
		}
		// If the send fails the job stays QUEUED and the sweeper re-publishes it: the request still
		// succeeds, which is better than failing it on a transient broker hiccup.
		publisher.publish(repo.id(), job.id());
		return new Submission(repo, job);
	}

	/**
	 * Self-healing for the gap between the database and Kafka (a full transactional outbox would
	 * close it completely): re-publish jobs whose message was lost, and fail jobs whose worker died.
	 * Re-publishing is safe because workers claim jobs atomically.
	 */
	@Scheduled(fixedDelayString = "${repomind.ingest.sweep-interval:60s}", initialDelayString = "30s")
	public void sweep() {
		for (IngestJob stale : jobs.findQueuedOlderThan(properties.staleQueuedAfter())) {
			log.info("Re-publishing stale queued job {}", stale.id());
			publisher.publish(stale.repositoryId(), stale.id());
		}
		List<UUID> stuckRepos = jobs.failRunningOlderThan(properties.stuckRunningAfter());
		for (UUID repoId : stuckRepos) {
			repos.findById(repoId).ifPresent(r -> repos.updateStatus(r.id(),
					r.activeJobId() != null ? RepoStatus.READY : RepoStatus.FAILED));
		}
		if (!stuckRepos.isEmpty()) {
			log.warn("Failed {} ingest jobs that stopped making progress", stuckRepos.size());
		}
	}

}
