package dev.repomind.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import dev.repomind.ingest.IngestJob;
import dev.repomind.ingest.IngestJobDao;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class RepoApiIT {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	RepoDao repos;

	@Autowired
	IngestJobDao jobs;

	@Test
	void rejectsInvalidRepositoryWithProblemDetail() {
		assertThat(post("{\"repository\": \"https://gitlab.com/a/b\"}")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("Not a GitHub repository");

		assertThat(post("{\"repository\": \"\"}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("{}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void returnsConflictWhileAJobIsRunning() {
		String name = "busy-" + UUID.randomUUID();
		Repo repo = repos.upsert("acme", name, "https://github.com/acme/" + name + ".git");
		IngestJob running = jobs.create(repo.id()); // QUEUED, never published

		assertThat(post("{\"repository\": \"acme/" + name + "\"}")).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.jobId")
			.isEqualTo(running.id().toString());
	}

	@Test
	void exposesRepositoryAndJobStatus() {
		String name = "status-" + UUID.randomUUID();
		Repo repo = repos.upsert("acme", name, "https://github.com/acme/" + name + ".git");
		IngestJob job = jobs.create(repo.id());

		assertThat(mvc.get().uri("/api/repos/{id}", repo.id())).hasStatusOk()
			.bodyJson()
			.extractingPath("$.latestJob.status")
			.isEqualTo("QUEUED");
		assertThat(mvc.get().uri("/api/jobs/{id}", job.id())).hasStatusOk()
			.bodyJson()
			.extractingPath("$.progressPercent")
			.isEqualTo(0);
		assertThat(mvc.get().uri("/api/repos/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri("/api/jobs/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
	}

	private MockMvcTester.MockMvcRequestBuilder post(String body) {
		return mvc.post().uri("/api/repos").contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
