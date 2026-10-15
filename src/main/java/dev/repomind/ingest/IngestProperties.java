package dev.repomind.ingest;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param workdir where repositories are cloned; each job gets its own directory, deleted afterwards
 * @param maxFileBytes larger source files (usually generated code) are skipped
 * @param maxFiles hard cap per repository to bound ingestion cost
 * @param embeddingBatchSize chunks per embedding request
 * @param cloneTimeout network timeout for the clone
 * @param staleQueuedAfter QUEUED jobs older than this are re-published (lost Kafka send)
 * @param stuckRunningAfter running jobs older than this are failed (worker died mid-job)
 */
@ConfigurationProperties("repomind.ingest")
public record IngestProperties(
		Path workdir,
		@DefaultValue("1000000") long maxFileBytes,
		@DefaultValue("20000") int maxFiles,
		@DefaultValue("32") int embeddingBatchSize,
		@DefaultValue("5m") Duration cloneTimeout,
		@DefaultValue("2m") Duration staleQueuedAfter,
		@DefaultValue("45m") Duration stuckRunningAfter) {
}
