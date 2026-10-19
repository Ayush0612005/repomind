package dev.repomind.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param apiUrl GitHub REST API root (GitHub Enterprise uses {@code https://host/api/v3})
 * @param token optional for reading public repositories (which then share a 60 requests/hour
 * limit); required to post reviews
 * @param webhookSecret the secret configured on the GitHub webhook; webhooks are rejected until it is set
 */
@ConfigurationProperties("repomind.github")
public record GitHubProperties(
		@DefaultValue("https://api.github.com") String apiUrl,
		String token,
		String webhookSecret) {
}
