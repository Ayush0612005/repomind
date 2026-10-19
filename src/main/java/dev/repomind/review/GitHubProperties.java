package dev.repomind.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param apiUrl GitHub REST API root (GitHub Enterprise uses {@code https://host/api/v3})
 * @param token optional; public repositories work without one but share a 60 requests/hour limit
 */
@ConfigurationProperties("repomind.github")
public record GitHubProperties(
		@DefaultValue("https://api.github.com") String apiUrl,
		String token) {
}
