package dev.repomind.api;

import dev.repomind.ingest.IngestService.JobAlreadyRunningException;
import dev.repomind.limits.BudgetExceededException;
import dev.repomind.limits.InvalidApiKeyException;
import dev.repomind.limits.RateLimitedException;
import dev.repomind.qa.ModelUnavailableException;
import dev.repomind.review.GitHubUnavailableException;
import dev.repomind.review.PullRequestNotFoundException;
import dev.repomind.review.WebhookRejectedException;
import dev.repomind.search.NotIndexedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps domain errors to RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail badRequest(IllegalArgumentException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail notFound(NotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(NotIndexedException.class)
	ProblemDetail notIndexed(NotIndexedException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(ModelUnavailableException.class)
	ProblemDetail modelUnavailable(ModelUnavailableException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
	}

	@ExceptionHandler(PullRequestNotFoundException.class)
	ProblemDetail pullRequestNotFound(PullRequestNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(GitHubUnavailableException.class)
	ProblemDetail gitHubUnavailable(GitHubUnavailableException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
	}

	@ExceptionHandler(WebhookRejectedException.class)
	ProblemDetail webhookRejected(WebhookRejectedException ex) {
		return ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
	}

	@ExceptionHandler(InvalidApiKeyException.class)
	ResponseEntity<ProblemDetail> invalidApiKey(InvalidApiKeyException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"repomind\"")
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage()));
	}

	@ExceptionHandler(RateLimitedException.class)
	ResponseEntity<ProblemDetail> rateLimited(RateLimitedException ex) {
		return tooManyRequests(ex.getMessage(), ex.decision().retryAfterSeconds(), "rate-limit");
	}

	@ExceptionHandler(BudgetExceededException.class)
	ResponseEntity<ProblemDetail> budgetExceeded(BudgetExceededException ex) {
		return tooManyRequests(ex.getMessage(), ex.retryAfterSeconds(), "token-budget");
	}

	private static ResponseEntity<ProblemDetail> tooManyRequests(String detail, long retryAfterSeconds, String limit) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, detail);
		problem.setProperty("limit", limit);
		problem.setProperty("retryAfterSeconds", retryAfterSeconds);
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
			.body(problem);
	}

	@ExceptionHandler(JobAlreadyRunningException.class)
	ProblemDetail conflict(JobAlreadyRunningException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setProperty("jobId", ex.job().id());
		problem.setProperty("jobStatus", ex.job().status());
		return problem;
	}

}
