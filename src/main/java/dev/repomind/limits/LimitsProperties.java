package dev.repomind.limits;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API clients and their limits.
 *
 * @param enabled off by default for local development; when on, every {@code /api} request is
 * identified and rate limited (except GitHub webhooks, which are authenticated by signature)
 * @param anonymous limits for requests without an API key, applied per client IP; null rejects
 * requests that have no key
 * @param clients known API clients
 */
@ConfigurationProperties("repomind.limits")
public record LimitsProperties(
		@DefaultValue("false") boolean enabled,
		Policy anonymous,
		@DefaultValue List<Client> clients) {

	/**
	 * @param requestsPerMinute sustained request rate; bursts up to the same number are allowed
	 * @param dailyTokens model tokens (prompt + completion) per UTC day; cache hits cost nothing
	 */
	public record Policy(int requestsPerMinute, long dailyTokens) {
	}

	/**
	 * @param keySha256 hex SHA-256 of the client's API key, so the configuration never holds the
	 * key itself: {@code printf %s "$KEY" | sha256sum}
	 */
	public record Client(String name, String keySha256, int requestsPerMinute, long dailyTokens) {

		Policy policy() {
			return new Policy(requestsPerMinute, dailyTokens);
		}

	}

}
