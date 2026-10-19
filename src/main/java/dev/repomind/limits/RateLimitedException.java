package dev.repomind.limits;

public class RateLimitedException extends RuntimeException {

	private final RateLimiter.Decision decision;

	public RateLimitedException(RateLimiter.Decision decision) {
		super("Rate limit of " + decision.limit() + " requests per minute exceeded; retry in "
				+ decision.retryAfterSeconds() + "s");
		this.decision = decision;
	}

	public RateLimiter.Decision decision() {
		return decision;
	}

}
