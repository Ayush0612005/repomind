package dev.repomind.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Progress of one ingestion run, persisted so the status API survives restarts. */
public record IngestJob(
		UUID id,
		UUID repositoryId,
		JobStatus status,
		int filesTotal,
		int filesParsed,
		int chunksTotal,
		int chunksEmbedded,
		int linesOfCode,
		String error,
		Instant createdAt,
		Instant startedAt,
		Instant finishedAt) {

	/** Overall progress in [0, 100]: parsing is weighted 30%, embedding 70%. */
	public int progressPercent() {
		if (status == JobStatus.COMPLETED) {
			return 100;
		}
		double parse = filesTotal == 0 ? 0 : (double) filesParsed / filesTotal;
		double embed = chunksTotal == 0 ? 0 : (double) chunksEmbedded / chunksTotal;
		return (int) Math.floor(parse * 30 + embed * 70);
	}

	public Duration elapsed() {
		if (startedAt == null) {
			return Duration.ZERO;
		}
		return Duration.between(startedAt, finishedAt != null ? finishedAt : Instant.now());
	}

}
