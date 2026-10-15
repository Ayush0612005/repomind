package dev.repomind.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.repomind.TestcontainersConfiguration;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.repo.RepoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class IngestJobDaoIT {

	@Autowired
	RepoDao repos;

	@Autowired
	IngestJobDao jobs;

	@Test
	void upsertIsIdempotentOnOwnerAndName() {
		Repo first = repos.upsert("spring-projects", "spring-petclinic", "https://github.com/spring-projects/spring-petclinic.git");
		Repo second = repos.upsert("spring-projects", "spring-petclinic", "https://github.com/spring-projects/spring-petclinic.git");

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.status()).isEqualTo(RepoStatus.PENDING);
		assertThat(repos.findByFullName("Spring-Projects", "Spring-PetClinic")).contains(second);
	}

	@Test
	void jobMovesThroughLifecycleAndTracksProgress() {
		Repo repo = repos.upsert("acme", "lifecycle", "https://github.com/acme/lifecycle.git");
		IngestJob job = jobs.create(repo.id());

		assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
		assertThat(jobs.hasActiveJob(repo.id())).isTrue();

		jobs.transition(job.id(), JobStatus.PARSING);
		jobs.recordParseResult(job.id(), 10, 10, 40, 1_200);
		jobs.transition(job.id(), JobStatus.EMBEDDING);
		jobs.addEmbedded(job.id(), 20);

		IngestJob midway = jobs.findById(job.id()).orElseThrow();
		assertThat(midway.startedAt()).isNotNull();
		assertThat(midway.finishedAt()).isNull();
		assertThat(midway.progressPercent()).isEqualTo(65); // 30% parse + half of 70% embed

		jobs.addEmbedded(job.id(), 20);
		jobs.transition(job.id(), JobStatus.COMPLETED);

		IngestJob done = jobs.findLatestForRepo(repo.id()).orElseThrow();
		assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
		assertThat(done.finishedAt()).isNotNull();
		assertThat(done.progressPercent()).isEqualTo(100);
		assertThat(jobs.hasActiveJob(repo.id())).isFalse();
	}

	@Test
	void failRecordsErrorAndFinishesJob() {
		Repo repo = repos.upsert("acme", "broken", "https://github.com/acme/broken.git");
		IngestJob job = jobs.create(repo.id());

		jobs.fail(job.id(), "clone failed: repository not found");

		IngestJob failed = jobs.findById(job.id()).orElseThrow();
		assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
		assertThat(failed.error()).contains("repository not found");
		assertThat(failed.finishedAt()).isNotNull();
	}

}
