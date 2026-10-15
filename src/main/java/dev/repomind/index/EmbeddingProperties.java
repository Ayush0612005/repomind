package dev.repomind.index;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param dimensions must match the {@code vector(N)} column in the schema
 * @param documentPrefix / queryPrefix task prefixes; nomic-embed-text was trained with
 * {@code search_document:} / {@code search_query:} and retrieves noticeably worse without them
 */
@ConfigurationProperties("repomind.embedding")
public record EmbeddingProperties(
		@DefaultValue("768") int dimensions,
		@DefaultValue("search_document: ") String documentPrefix,
		@DefaultValue("search_query: ") String queryPrefix) {
}
