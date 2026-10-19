package dev.repomind.qa;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retrieveLimit chunks retrieved per question before the context budget is applied
 * @param maxContextTokens approximate token budget for the code sent to the model; lower-ranked
 * sources are dropped first, so the prompt cost per question is bounded however large the chunks are
 */
@ConfigurationProperties("repomind.qa")
public record QaProperties(
		@DefaultValue("8") int retrieveLimit,
		@DefaultValue("6000") int maxContextTokens) {
}
