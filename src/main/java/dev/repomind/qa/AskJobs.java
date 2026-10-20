package dev.repomind.qa;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/**
 * Answers that run longer than one HTTP request should stay open. A slow model (a local CPU model
 * can take minutes) would otherwise hit the idle timeout of whatever proxy sits in front of the
 * service, so callers submit a question and fetch the answer by job id instead.
 *
 * <p>Jobs live in memory: they are short-lived, and losing one on restart only means asking again.
 * Each job is visible only to the client that submitted it, and finished jobs are dropped after
 * {@link #RETENTION}.
 */
@Component
public class AskJobs {

	static final Duration RETENTION = Duration.ofMinutes(15);

	static final int MAX_RUNNING = 50;

	private record Job(String owner, CompletableFuture<Answer> answer, Instant created) {
	}

	private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public UUID submit(String owner, Supplier<Answer> work) {
		evictExpired();
		if (jobs.values().stream().filter(job -> !job.answer().isDone()).count() >= MAX_RUNNING) {
			throw new ModelUnavailableException("Too many questions are being answered; try again shortly", null);
		}
		UUID id = UUID.randomUUID();
		jobs.put(id, new Job(owner, CompletableFuture.supplyAsync(work, executor), Instant.now()));
		return id;
	}

	/** True if {@code owner} submitted job {@code id} and it has not expired. */
	public boolean exists(UUID id, String owner) {
		Job job = jobs.get(id);
		return job != null && job.owner().equals(owner);
	}

	/**
	 * Waits up to {@code wait} for the answer.
	 * @return the answer, or empty if it is still being worked on
	 * @throws RuntimeException the exception answering failed with, so it maps to the same error
	 * response as a synchronous request
	 */
	public Optional<Answer> await(UUID id, Duration wait) {
		CompletableFuture<Answer> answer = jobs.get(id).answer();
		try {
			return Optional.of(answer.get(wait.toMillis(), TimeUnit.MILLISECONDS));
		}
		catch (TimeoutException ex) {
			return Optional.empty();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(ex.getCause());
		}
	}

	private void evictExpired() {
		Instant cutoff = Instant.now().minus(RETENTION);
		jobs.values().removeIf(job -> job.answer().isDone() && job.created().isBefore(cutoff));
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

}
