package dev.repomind.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds on what one review sends to the model, so cost stays predictable on large pull requests.
 *
 * @param maxFiles Java files reviewed per pull request; the rest are listed as skipped
 * @param maxDiffTokens approximate token budget for the diffs
 * @param contextPerFile related chunks retrieved for each reviewed file
 * @param maxContextTokens approximate token budget for related code
 * @param maxComments comments kept after validation, most severe first
 */
@ConfigurationProperties("repomind.review")
public record ReviewProperties(
		@DefaultValue("25") int maxFiles,
		@DefaultValue("12000") int maxDiffTokens,
		@DefaultValue("3") int contextPerFile,
		@DefaultValue("4000") int maxContextTokens,
		@DefaultValue("30") int maxComments) {
}
