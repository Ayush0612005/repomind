package dev.repomind.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import dev.repomind.ingest.IngestJob;
import dev.repomind.ingest.IngestJobDao;
import dev.repomind.ingest.JobStatus;
import dev.repomind.parse.CodeChunk;
import dev.repomind.parse.JavaChunker;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.support.HashingEmbeddingModel;
import dev.repomind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class ChunkStoreIT {

	@Autowired
	ChunkStore store;

	@Autowired
	EmbeddingService embeddings;

	@Autowired
	HashingEmbeddingModel model;

	@Autowired
	RepoDao repos;

	@Autowired
	IngestJobDao jobs;

	@Autowired
	JdbcClient jdbc;

	private final JavaChunker chunker = new JavaChunker();

	@Test
	void embedsGenerationAndReusesUnchangedEmbeddingsOnReindex() {
		Repo repo = repos.upsert("acme", "reuse-" + UUID.randomUUID(), "https://github.com/acme/reuse.git");

		// generation 1: two classes
		UUID first = completedJob(repo.id());
		store.insert(repo.id(), first, chunks("A.java", "class A { int sum(int x, int y) { int z = x + y; return z * 2; } }"));
		store.insert(repo.id(), first, chunks("B.java", "class B { void run() { System.out.println(1); System.out.println(2); } }"));
		embedAll(first);
		store.activate(repo.id(), first);
		int embeddedAfterFirst = model.embeddedTexts();

		// generation 2: A unchanged, B edited
		UUID second = completedJob(repo.id());
		store.insert(repo.id(), second, chunks("A.java", "class A { int sum(int x, int y) { int z = x + y; return z * 2; } }"));
		store.insert(repo.id(), second, chunks("B.java", "class B { void run() { System.out.println(3); System.out.println(4); } }"));

		int reused = store.reuseEmbeddings(repo.id(), second);
		List<ChunkStore.PendingChunk> pending = store.findUnembedded(second, 100);

		// A's type + method and B's type skeleton (only a method body changed) are reused
		assertThat(reused).isEqualTo(3);
		assertThat(pending).singleElement()
			.satisfies(p -> assertThat(p.text()).startsWith("// B.java\n// method B#run()"));

		embedAll(second);
		assertThat(model.embeddedTexts()).isEqualTo(embeddedAfterFirst + 1); // only B#run was re-embedded

		store.activate(repo.id(), second);
		assertThat(store.countGeneration(first)).isZero();
		assertThat(store.countGeneration(second)).isEqualTo(4);
		assertThat(jdbc.sql("SELECT active_job_id FROM repositories WHERE id = :id")
			.param("id", repo.id())
			.query(UUID.class)
			.single()).isEqualTo(second);
	}

	@Test
	void storesNulFreeContent() {
		Repo repo = repos.upsert("acme", "nul-" + UUID.randomUUID(), "https://github.com/acme/nul.git");
		UUID job = completedJob(repo.id());

		store.insert(repo.id(), job, chunks("N.java", "class N { String s() { String x = \"a\0b\"; return x + x; } }"));

		assertThat(store.countGeneration(job)).isEqualTo(2);
	}

	private UUID completedJob(UUID repoId) {
		IngestJob job = jobs.create(repoId);
		jobs.transition(job.id(), JobStatus.COMPLETED); // free the one-active-job slot for the next one
		return job.id();
	}

	private void embedAll(UUID jobId) {
		List<ChunkStore.PendingChunk> pending;
		while (!(pending = store.findUnembedded(jobId, 32)).isEmpty()) {
			List<float[]> vectors = embeddings.embedDocuments(pending.stream().map(ChunkStore.PendingChunk::text).toList());
			store.saveEmbeddings(pending.stream().map(ChunkStore.PendingChunk::id).toList(), vectors);
		}
	}

	private List<CodeChunk> chunks(String path, String source) {
		return chunker.chunk(path, source).chunks();
	}

}
