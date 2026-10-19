package dev.repomind.api;

import java.util.UUID;

import dev.repomind.limits.ApiClient;
import dev.repomind.limits.TokenBudget;
import dev.repomind.qa.Answer;
import dev.repomind.qa.AnswerService;
import dev.repomind.repo.RepoDao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
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

	public AskController(AnswerService answers, RepoDao repos, TokenBudget budgets) {
		this.answers = answers;
		this.repos = repos;
		this.budgets = budgets;
	}

	/** Answers a question about the repository's code, citing {@code file:line} for each claim. */
	@PostMapping("/api/repos/{id}/ask")
	ResponseEntity<Answer> ask(@PathVariable UUID id, @Valid @RequestBody AskRequest request,
			@RequestAttribute(ApiClient.ATTRIBUTE) ApiClient client) {
		var repo = repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
		Answer answer = answers.ask(repo, request.question(), budgets.forClient(client));
		return BudgetHeaders.with(ResponseEntity.ok(), budgets, client).body(answer);
	}

}
