package dev.repomind.repo;

import java.time.Instant;
import java.util.UUID;

/** A GitHub repository tracked by RepoMind. */
public record Repo(
		UUID id,
		String owner,
		String name,
		String cloneUrl,
		String defaultBranch,
		String commitSha,
		RepoStatus status,
		Instant createdAt,
		Instant updatedAt) {

	public String fullName() {
		return owner + "/" + name;
	}

}
