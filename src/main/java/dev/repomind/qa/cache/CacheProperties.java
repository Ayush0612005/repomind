package dev.repomind.qa.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled turns both cache tiers off, e.g. while evaluating models
 * @param ttl how long an answer may be reused; the index generation bounds it as well
 * @param semanticThreshold minimum cosine similarity for reusing another question's answer. Set
 * high on purpose: "how many books can a premium member borrow" and "…a standard member…" embed
 * very closely yet have different answers, so a loose threshold serves wrong answers quickly
 */
@ConfigurationProperties("repomind.cache")
public record CacheProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("24h") Duration ttl,
		@DefaultValue("true") boolean semanticEnabled,
		@DefaultValue("0.97") double semanticThreshold) {
}
