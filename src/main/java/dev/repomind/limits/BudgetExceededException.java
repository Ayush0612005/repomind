package dev.repomind.limits;

public class BudgetExceededException extends RuntimeException {

	private final long retryAfterSeconds;

	public BudgetExceededException(long dailyTokens, long retryAfterSeconds) {
		super("Daily model token budget of " + dailyTokens
				+ " is used up; it resets at midnight UTC. Cached answers are still served.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
