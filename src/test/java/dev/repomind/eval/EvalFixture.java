package dev.repomind.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import dev.repomind.ingest.IngestJob;
import dev.repomind.ingest.IngestJobDao;
import dev.repomind.ingest.IngestMessaging;
import dev.repomind.ingest.JobStatus;
import dev.repomind.ingest.LocalGitRepo;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;

/** The library fixture codebase in {@code src/test/resources/eval/library}, ingested through the real pipeline. */
final class EvalFixture {

	private EvalFixture() {
	}

	/** Relative path → source of every file in the fixture. */
	static Map<String, String> files() throws IOException, URISyntaxException {
		Path root = Path.of(EvalFixture.class.getResource("/eval/library").toURI());
		Map<String, String> files = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path p : paths.filter(Files::isRegularFile).toList()) {
				files.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
			}
		}
		return files;
	}

	/** Commits the files to a local git repository and ingests it via Kafka, as a real repository would be. */
	static Repo ingest(Path dir, Map<String, String> files, RepoDao repos, IngestJobDao jobs,
			IngestMessaging.Publisher publisher, Duration timeout) throws Exception {
		String url = LocalGitRepo.create(dir, files);
		Repo repo = repos.upsert("eval", "library-" + UUID.randomUUID(), url);
		IngestJob job = jobs.create(repo.id());
		assertThat(publisher.publish(repo.id(), job.id())).isTrue();
		IngestJob done = await().atMost(timeout)
			.pollInterval(Duration.ofMillis(250))
			.until(() -> jobs.findById(job.id()).orElseThrow(), j -> j.status().isTerminal());
		assertThat(done.status()).as("fixture ingestion: %s", done.error()).isEqualTo(JobStatus.COMPLETED);
		return repos.findById(repo.id()).orElseThrow();
	}

}
