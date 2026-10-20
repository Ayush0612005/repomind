package dev.repomind.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClonerAndScannerTest {

	@TempDir
	Path temp;

	private IngestProperties properties(long maxFileBytes, int maxFiles) {
		return new IngestProperties(temp.resolve("work"), maxFileBytes, maxFiles, 32, Duration.ofSeconds(30),
				Duration.ofMinutes(2), Duration.ofMinutes(45));
	}

	@Test
	void clonesAndScansOnlyRelevantJavaSources() throws Exception {
		String url = LocalGitRepo.create(temp.resolve("origin"), Map.of(
				"src/main/java/a/App.java", "package a; class App {}",
				"src/test/java/a/AppTest.java", "package a; class AppTest {}",
				"src/test/resources/fixtures/Sample.java", "class Sample {}",
				"src/main/resources/templates/Kept.java", "class Kept {}",
				"target/generated/Gen.java", "class Gen {}",
				"build/Out.java", "class Out {}",
				"README.md", "# readme",
				"src/main/java/a/Huge.java", "class Huge { /*" + "x".repeat(5_000) + "*/ }"));
		IngestProperties props = properties(1_000, 100);
		RepoCloner cloner = new RepoCloner(props);

		RepoCloner.Checkout checkout = cloner.cloneShallow(UUID.randomUUID(), url);
		SourceScanner.Scan scan = new SourceScanner(props).scan(checkout.directory());

		assertThat(checkout.branch()).isEqualTo("main");
		assertThat(checkout.commitSha()).hasSize(40);
		assertThat(scan.files()).extracting(SourceScanner.SourceFile::path)
			.containsExactly("src/main/java/a/App.java", "src/main/resources/templates/Kept.java",
					"src/test/java/a/AppTest.java");
		assertThat(scan.skippedTooLarge()).isEqualTo(1);
		assertThat(scan.truncated()).isFalse();

		cloner.delete(checkout.directory());
		assertThat(Files.exists(checkout.directory())).isFalse();
	}

	@Test
	void capsNumberOfFiles() throws Exception {
		String url = LocalGitRepo.create(temp.resolve("origin"),
				Map.of("A.java", "class A {}", "B.java", "class B {}", "C.java", "class C {}"));
		IngestProperties props = properties(1_000, 2);

		RepoCloner.Checkout checkout = new RepoCloner(props).cloneShallow(UUID.randomUUID(), url);
		SourceScanner.Scan scan = new SourceScanner(props).scan(checkout.directory());

		assertThat(scan.files()).hasSize(2);
		assertThat(scan.truncated()).isTrue();
	}

	@Test
	void reportsCloneFailures() {
		RepoCloner cloner = new RepoCloner(properties(1_000, 10));

		assertThatExceptionOfType(IngestException.class)
			.isThrownBy(() -> cloner.cloneShallow(UUID.randomUUID(), temp.resolve("missing").toUri().toString()))
			.withMessageContaining("Clone failed");
	}

}
