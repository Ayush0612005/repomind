package dev.repomind.api;

import java.time.Instant;
import java.util.UUID;

import dev.repomind.ingest.IngestJob;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoStatus;

public record RepoResponse(
		UUID id,
		String fullName,
		RepoStatus status,
		String defaultBranch,
		String commitSha,
		JobResponse latestJob,
		Instant createdAt,
		Instant updatedAt) {

	static RepoResponse of(Repo repo, IngestJob latestJob) {
		return new RepoResponse(repo.id(), repo.fullName(), repo.status(), repo.defaultBranch(), repo.commitSha(),
				latestJob == null ? null : JobResponse.of(latestJob), repo.createdAt(), repo.updatedAt());
	}

}
