package dev.repomind.limits;

/**
 * The caller of a request, as resolved from its API key.
 *
 * @param id the key that limits and budgets are counted under: the client name, or
 * {@code anonymous:<ip>} for unauthenticated callers
 */
public record ApiClient(String id, LimitsProperties.Policy policy) {

	/** Request attribute holding the resolved client. */
	public static final String ATTRIBUTE = "dev.repomind.limits.ApiClient";

	/** Used when limits are disabled. */
	public static final ApiClient UNLIMITED = new ApiClient("unlimited",
			new LimitsProperties.Policy(Integer.MAX_VALUE, Long.MAX_VALUE));

	public boolean unlimited() {
		return this == UNLIMITED;
	}

}
