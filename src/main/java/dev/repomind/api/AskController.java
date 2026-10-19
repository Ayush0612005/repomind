package dev.repomind.api;

import java.util.UUID;

import dev.repomind.qa.Answer;
import dev.repomind.qa.AnswerService;
import dev.repomind.repo.RepoDao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

	public record AskRequest(@NotBlank @Size(max = 2_000) String question) {
	}

	private final AnswerService answers;

	private final RepoDao repos;

	public AskController(AnswerService answers, RepoDao repos) {
		this.answers = answers;
		this.repos = repos;
	}

	/** Answers a question about the repository's code, citing {@code file:line} for each claim. */
	@PostMapping("/api/repos/{id}/ask")
	Answer ask(@PathVariable UUID id, @Valid @RequestBody AskRequest request) {
		var repo = repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
		return answers.ask(repo, request.question());
	}

}
