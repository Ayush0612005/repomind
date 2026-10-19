package dev.repomind.review;

public class PullRequestNotFoundException extends RuntimeException {

	public PullRequestNotFoundException(String message) {
		super(message);
	}

}
