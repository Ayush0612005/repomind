package dev.repomind.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import dev.repomind.index.ChunkStore;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.repo.RepoStatus;
import dev.repomind.support.HashingEmbeddingModel;
import dev.repomind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Full asynchronous flow: Kafka message → claim → clone → chunk → embed → activate. */
@IntegrationTest
class IngestFlowIT {

	@TempDir
	Path temp;

	@Autowired
	RepoDao repos;

	@Autowired
	IngestJobDao jobs;

	@Autowired
	IngestMessaging.Publisher publisher;

	@Autowired
	ChunkStore store;

	@Autowired
	HashingEmbeddingModel model;

	@Autowired
	IngestProperties properties;

	@Autowired
	JdbcClient jdbc;

	@Test
	void ingestsRepositoryThroughKafkaAndReindexesWithoutReEmbedding() throws Exception {
		String url = LocalGitRepo.create(temp.resolve("origin"), Map.of(
				"src/main/java/org/acme/petclinic/owner/OwnerService.java", fixture("OwnerService.java"),
				"src/main/java/org/acme/petclinic/vet/VetController.java", """
						package org.acme.petclinic.vet;

						class VetController {
							String showVetList(int page) {
								if (page < 1) {
									throw new IllegalArgumentException("page must be positive");
								}
								return "vets/vetList";
							}
						}
						"""));
		Repo repo = repos.upsert("local", "petclinic-" + UUID.randomUUID(), url);

		IngestJob first = runToCompletion(repo);

		assertThat(first.status()).isEqualTo(JobStatus.COMPLETED);
		assertThat(first.filesTotal()).isEqualTo(2);
		assertThat(first.filesParsed()).isEqualTo(2);
		assertThat(first.chunksTotal()).isEqualTo(10);
		assertThat(first.chunksEmbedded()).isEqualTo(first.chunksTotal());
		assertThat(first.linesOfCode()).isGreaterThan(50);
		assertThat(first.progressPercent()).isEqualTo(100);

		Repo ready = repos.findById(repo.id()).orElseThrow();
		assertThat(ready.status()).isEqualTo(RepoStatus.READY);
		assertThat(ready.activeJobId()).isEqualTo(first.id());
		assertThat(ready.defaultBranch()).isEqualTo("main");
		assertThat(ready.commitSha()).hasSize(40);
		assertThat(store.countGeneration(first.id())).isEqualTo(10);
		assertThat(unembedded(first.id())).isZero();
		assertThat(Files.exists(properties.workdir().resolve(first.id().toString()))).isFalse();

		// unchanged re-index: every vector is reused, nothing is sent to the model
		int embeddedBefore = model.embeddedTexts();
		IngestJob second = runToCompletion(repo);

		assertThat(second.status()).isEqualTo(JobStatus.COMPLETED);
		assertThat(second.chunksEmbedded()).isEqualTo(second.chunksTotal());
		assertThat(model.embeddedTexts()).isEqualTo(embeddedBefore);
		assertThat(store.countGeneration(first.id())).isZero();
		assertThat(repos.findById(repo.id()).orElseThrow().activeJobId()).isEqualTo(second.id());
	}

	@Test
	void recordsFailureWhenCloneFails() {
		Repo repo = repos.upsert("local", "missing-" + UUID.randomUUID(),
				temp.resolve("does-not-exist").toUri().toString());

		IngestJob job = runToCompletion(repo);

		assertThat(job.status()).isEqualTo(JobStatus.FAILED);
		assertThat(job.error()).contains("Clone failed");
		assertThat(repos.findById(repo.id()).orElseThrow().status()).isEqualTo(RepoStatus.FAILED);
	}

	@Test
	void duplicateDeliveryIsIgnored() throws Exception {
		String url = LocalGitRepo.create(temp.resolve("origin"), Map.of("A.java", "class A { void a() { a(); a(); } }"));
		Repo repo = repos.upsert("local", "dup-" + UUID.randomUUID(), url);
		IngestJob job = jobs.create(repo.id());

		publisher.publish(repo.id(), job.id());
		publisher.publish(repo.id(), job.id()); // simulated redelivery

		IngestJob done = awaitTerminal(job.id());
		assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
		assertThat(store.countGeneration(job.id())).isEqualTo(2);
	}

	private IngestJob runToCompletion(Repo repo) {
		IngestJob job = jobs.create(repo.id());
		assertThat(publisher.publish(repo.id(), job.id())).isTrue();
		return awaitTerminal(job.id());
	}

	private IngestJob awaitTerminal(UUID jobId) {
		return await().atMost(Duration.ofSeconds(60))
			.pollInterval(Duration.ofMillis(200))
			.until(() -> jobs.findById(jobId).orElseThrow(), j -> j.status().isTerminal());
	}

	private int unembedded(UUID jobId) {
		return jdbc.sql("SELECT count(*) FROM code_chunks WHERE ingest_job_id = :job AND embedding IS NULL")
			.param("job", jobId)
			.query(Integer.class)
			.single();
	}

	private static String fixture(String name) throws IOException {
		try (InputStream in = IngestFlowIT.class.getResourceAsStream("/fixtures/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
