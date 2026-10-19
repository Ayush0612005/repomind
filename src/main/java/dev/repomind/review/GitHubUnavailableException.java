package dev.repomind.review;

/** GitHub could not be reached, rate-limited the request, or rejected the credentials. */
public class GitHubUnavailableException extends RuntimeException {

	public GitHubUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
