package dev.repomind.ingest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.repomind.index.ChunkStore;
import dev.repomind.index.ChunkStore.PendingChunk;
import dev.repomind.index.EmbeddingService;
import dev.repomind.parse.CodeChunk;
import dev.repomind.parse.JavaChunker;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.repo.RepoStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs one ingestion job end to end: clone → scan → AST chunk → store → embed → activate.
 * Progress is written to {@code ingest_jobs} after every stage and embedding batch.
 */
@Service
public class IngestionPipeline {

	private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);

	private static final int INSERT_BATCH = 500;

	private final RepoDao repos;

	private final IngestJobDao jobs;

	private final RepoCloner cloner;

	private final SourceScanner scanner;

	private final JavaChunker chunker = new JavaChunker();

	private final ChunkStore store;

	private final EmbeddingService embeddings;

	private final IngestProperties properties;

	private final Timer duration;

	private final Counter chunksEmbedded;

	private final Counter chunksReused;

	public IngestionPipeline(RepoDao repos, IngestJobDao jobs, RepoCloner cloner, SourceScanner scanner,
			ChunkStore store, EmbeddingService embeddings, IngestProperties properties, MeterRegistry meters) {
		this.repos = repos;
		this.jobs = jobs;
		this.cloner = cloner;
		this.scanner = scanner;
		this.store = store;
		this.embeddings = embeddings;
		this.properties = properties;
		this.duration = Timer.builder("repomind.ingest.duration").register(meters);
		this.chunksEmbedded = Counter.builder("repomind.ingest.chunks").tag("source", "embedded").register(meters);
		this.chunksReused = Counter.builder("repomind.ingest.chunks").tag("source", "reused").register(meters);
	}

	/**
	 * Processes the job if it is still QUEUED. Kafka delivers at least once, so a redelivered or
	 * re-published message for a job that another worker already claimed is a no-op.
	 */
	public void run(UUID jobId) {
		if (!jobs.claim(jobId)) {
			log.info("Job {} already claimed or finished, skipping", jobId);
			return;
		}
		IngestJob job = jobs.findById(jobId).orElseThrow();
		Repo repo = repos.findById(job.repositoryId()).orElseThrow();
		repos.updateStatus(repo.id(), RepoStatus.INGESTING);
		log.info("Ingesting {} (job {})", repo.fullName(), jobId);

		Timer.Sample sample = Timer.start();
		RepoCloner.Checkout checkout = null;
		try {
			checkout = cloner.cloneShallow(jobId, repo.cloneUrl());

			jobs.transition(jobId, JobStatus.PARSING);
			int chunkCount = parseAndStore(repo, jobId, checkout);

			jobs.transition(jobId, JobStatus.EMBEDDING);
			int reused = store.reuseEmbeddings(repo.id(), jobId);
			jobs.addEmbedded(jobId, reused);
			chunksReused.increment(reused);
			embedRemaining(jobId);

			store.activate(repo.id(), jobId);
			repos.markReady(repo.id(), checkout.branch(), checkout.commitSha());
			jobs.transition(jobId, JobStatus.COMPLETED);
			log.info("Ingested {}: {} chunks ({} reused) at {}", repo.fullName(), chunkCount, reused,
					checkout.commitSha());
		}
		catch (RuntimeException | IOException ex) {
			log.warn("Ingestion of {} failed: {}", repo.fullName(), ex.getMessage(), ex);
			jobs.fail(jobId, summarize(ex));
			store.deleteGeneration(jobId);
			// a failed re-index leaves the previous generation serving queries
			boolean hasIndex = repos.findById(repo.id()).map(r -> r.activeJobId() != null).orElse(false);
			repos.updateStatus(repo.id(), hasIndex ? RepoStatus.READY : RepoStatus.FAILED);
		}
		finally {
			sample.stop(duration);
			if (checkout != null) {
				cloner.delete(checkout.directory());
			}
		}
	}

	private int parseAndStore(Repo repo, UUID jobId, RepoCloner.Checkout checkout) throws IOException {
		SourceScanner.Scan scan = scanner.scan(checkout.directory());
		if (scan.files().isEmpty()) {
			throw new IngestException("No Java sources found in " + repo.fullName());
		}
		if (scan.truncated()) {
			log.warn("{} has more than {} Java files; indexing the first {}", repo.fullName(), properties.maxFiles(),
					properties.maxFiles());
		}

		List<CodeChunk> batch = new ArrayList<>(INSERT_BATCH);
		int files = 0;
		int chunks = 0;
		int loc = 0;
		int unparsed = 0;
		for (SourceScanner.SourceFile file : scan.files()) {
			JavaChunker.Result result = chunker.chunk(file.path(), file.content());
			files++;
			loc += result.linesOfCode();
			if (!result.parsed()) {
				unparsed++;
			}
			batch.addAll(result.chunks());
			chunks += result.chunks().size();
			if (batch.size() >= INSERT_BATCH) {
				store.insert(repo.id(), jobId, batch);
				batch.clear();
				jobs.recordParseResult(jobId, scan.files().size(), files, chunks, loc);
			}
		}
		if (!batch.isEmpty()) {
			store.insert(repo.id(), jobId, batch);
		}
		jobs.recordParseResult(jobId, scan.files().size(), files, chunks, loc);
		if (unparsed > 0) {
			log.info("{}: {} of {} files fell back to line fragments", repo.fullName(), unparsed, files);
		}
		return chunks;
	}

	private void embedRemaining(UUID jobId) {
		List<PendingChunk> pending;
		while (!(pending = store.findUnembedded(jobId, properties.embeddingBatchSize())).isEmpty()) {
			List<float[]> vectors = embeddings.embedDocuments(pending.stream().map(PendingChunk::text).toList());
			store.saveEmbeddings(pending.stream().map(PendingChunk::id).toList(), vectors);
			jobs.addEmbedded(jobId, pending.size());
			chunksEmbedded.increment(pending.size());
		}
	}

	private static String summarize(Throwable ex) {
		String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
		return message.length() > 1_000 ? message.substring(0, 1_000) + "…" : message;
	}

}
