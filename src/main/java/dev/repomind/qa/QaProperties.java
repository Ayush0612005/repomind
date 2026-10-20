package dev.repomind.qa;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retrieveLimit chunks retrieved per question before the context budget is applied
 * @param maxContextTokens approximate token budget for the code sent to the model; lower-ranked
 * sources are dropped first, so the prompt cost per question is bounded however large the chunks are
 * @param jobWait how long an asynchronous ask request waits for the answer before replying
 * {@code 202 Accepted}; kept under the idle timeouts of reverse proxies (often 60 s)
 */
@ConfigurationProperties("repomind.qa")
public record QaProperties(
		@DefaultValue("8") int retrieveLimit,
		@DefaultValue("6000") int maxContextTokens,
		@DefaultValue("20s") Duration jobWait) {
}
