package dev.repomind.api;

import dev.repomind.review.WebhookHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Receives GitHub webhooks; configure it with content type {@code application/json} and a secret. */
@RestController
public class GitHubWebhookController {

	private final WebhookHandler handler;

	public GitHubWebhookController(WebhookHandler handler) {
		this.handler = handler;
	}

	/** The raw body is read as bytes because the signature covers the exact bytes GitHub sent. */
	@PostMapping("/api/github/webhook")
	ResponseEntity<WebhookHandler.Outcome> receive(@RequestHeader("X-GitHub-Event") String event,
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
			@RequestBody byte[] payload) {
		WebhookHandler.Outcome outcome = handler.handle(event, signature, payload);
		return ResponseEntity.status("queued".equals(outcome.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
			.body(outcome);
	}

}
