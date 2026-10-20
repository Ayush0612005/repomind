package dev.repomind.api;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import dev.repomind.limits.ApiClient;
import dev.repomind.limits.TokenBudget;
import dev.repomind.qa.Answer;
import dev.repomind.qa.AnswerService;
import dev.repomind.qa.AskJobs;
import dev.repomind.qa.QaProperties;
import dev.repomind.repo.RepoDao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

	public record AskRequest(@NotBlank @Size(max = 2_000) String question) {
	}

	private final AnswerService answers;

	private final RepoDao repos;

	private final TokenBudget budgets;

	private final AskJobs jobs;

	private final QaProperties properties;

	public AskController(AnswerService answers, RepoDao repos, TokenBudget budgets, AskJobs jobs,
			QaProperties properties) {
		this.answers = answers;
		this.repos = repos;
		this.budgets = budgets;
		this.jobs = jobs;
		this.properties = properties;
	}

	/** Answers a question about the repository's code, citing {@code file:line} for each claim. */
	@PostMapping("/api/repos/{id}/ask")
	ResponseEntity<Answer> ask(@PathVariable UUID id, @Valid @RequestBody AskRequest request,
			@RequestAttribute(ApiClient.ATTRIBUTE) ApiClient client) {
		var repo = repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
		Answer answer = answers.ask(repo, request.question(), budgets.forClient(client));
		return BudgetHeaders.with(ResponseEntity.ok(), budgets, client).body(answer);
	}

	/**
	 * Same as {@link #ask}, for clients behind a proxy that would time out a slow answer: waits up to
	 * {@code repomind.qa.job-wait} and replies {@code 200} with the answer if it is ready (cached
	 * answers always are), otherwise {@code 202} with a job to fetch the answer from.
	 */
	@PostMapping("/api/repos/{id}/ask-jobs")
	ResponseEntity<?> submit(@PathVariable UUID id, @Valid @RequestBody AskRequest request,
			@RequestAttribute(ApiClient.ATTRIBUTE) ApiClient client) {
		var repo = repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
		UUID job = jobs.submit(client.id(), () -> answers.ask(repo, request.question(), budgets.forClient(client)));
		return result(job, client);
	}

	/** A submitted question's answer: {@code 200} when ready, {@code 202} while it is being worked on. */
	@GetMapping("/api/ask-jobs/{job}")
	ResponseEntity<?> result(@PathVariable UUID job, @RequestAttribute(ApiClient.ATTRIBUTE) ApiClient client) {
		if (!jobs.exists(job, client.id())) {
			throw new NotFoundException("No question " + job);
		}
		return jobs.await(job, properties.jobWait())
			.<ResponseEntity<?>>map(answer -> BudgetHeaders.with(ResponseEntity.ok(), budgets, client).body(answer))
			.orElseGet(() -> ResponseEntity.accepted()
				.location(URI.create("/api/ask-jobs/" + job))
				.body(Map.of("id", job, "status", "RUNNING")));
	}

}
