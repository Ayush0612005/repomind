package dev.repomind.search;

import dev.repomind.repo.Repo;

/** The repository has no completed index generation to search yet. */
public class NotIndexedException extends RuntimeException {

	public NotIndexedException(Repo repo) {
		super(repo.fullName() + " has not been indexed yet (status " + repo.status() + ")");
	}

}
