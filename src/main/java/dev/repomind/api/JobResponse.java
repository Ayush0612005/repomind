package dev.repomind.api;

import java.time.Instant;
import java.util.UUID;

import dev.repomind.ingest.IngestJob;
import dev.repomind.ingest.JobStatus;

public record JobResponse(
		UUID id,
		UUID repositoryId,
		JobStatus status,
		int progressPercent,
		int filesTotal,
		int filesParsed,
		int chunksTotal,
		int chunksEmbedded,
		int linesOfCode,
		long elapsedSeconds,
		String error,
		Instant createdAt,
		Instant startedAt,
		Instant finishedAt) {

	static JobResponse of(IngestJob job) {
		return new JobResponse(job.id(), job.repositoryId(), job.status(), job.progressPercent(), job.filesTotal(),
				job.filesParsed(), job.chunksTotal(), job.chunksEmbedded(), job.linesOfCode(),
				job.elapsed().toSeconds(), job.error(), job.createdAt(), job.startedAt(), job.finishedAt());
	}

}
