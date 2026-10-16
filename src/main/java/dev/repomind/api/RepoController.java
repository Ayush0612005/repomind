package dev.repomind.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import dev.repomind.ingest.IngestJobDao;
import dev.repomind.ingest.IngestService;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RepoController {

	/** @param repository {@code owner/name} or a GitHub URL */
	public record IngestRequest(@NotBlank String repository) {
	}

	public record SubmissionResponse(RepoResponse repository, JobResponse job) {
	}

	private final IngestService ingest;

	private final RepoDao repos;

	private final IngestJobDao jobs;

	public RepoController(IngestService ingest, RepoDao repos, IngestJobDao jobs) {
		this.ingest = ingest;
		this.repos = repos;
		this.jobs = jobs;
	}

	/** Queues ingestion and returns immediately; poll the job's Location for progress. */
	@PostMapping("/repos")
	ResponseEntity<SubmissionResponse> ingest(@Valid @RequestBody IngestRequest request) {
		return accepted(ingest.submit(request.repository()));
	}

	@PostMapping("/repos/{id}/reindex")
	ResponseEntity<SubmissionResponse> reindex(@PathVariable UUID id) {
		return accepted(ingest.reindex(find(id)));
	}

	@GetMapping("/repos")
	List<RepoResponse> list() {
		return repos.findAll()
			.stream()
			.map(r -> RepoResponse.of(r, jobs.findLatestForRepo(r.id()).orElse(null)))
			.toList();
	}

	@GetMapping("/repos/{id}")
	RepoResponse get(@PathVariable UUID id) {
		Repo repo = find(id);
		return RepoResponse.of(repo, jobs.findLatestForRepo(id).orElse(null));
	}

	@GetMapping("/jobs/{id}")
	JobResponse job(@PathVariable UUID id) {
		return jobs.findById(id).map(JobResponse::of).orElseThrow(() -> new NotFoundException("No job " + id));
	}

	private Repo find(UUID id) {
		return repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
	}

	private static ResponseEntity<SubmissionResponse> accepted(IngestService.Submission submission) {
		return ResponseEntity.accepted()
			.location(URI.create("/api/jobs/" + submission.job().id()))
			.body(new SubmissionResponse(RepoResponse.of(submission.repository(), submission.job()),
					JobResponse.of(submission.job())));
	}

}
