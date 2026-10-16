package dev.repomind.repo;

import java.time.Instant;
import java.util.UUID;

/** A GitHub repository tracked by RepoMind. {@code activeJobId} is the chunk generation served to queries. */
public record Repo(
		UUID id,
		String owner,
		String name,
		String cloneUrl,
		String defaultBranch,
		String commitSha,
		UUID activeJobId,
		RepoStatus status,
		Instant createdAt,
		Instant updatedAt) {

	public String fullName() {
		return owner + "/" + name;
	}

}
