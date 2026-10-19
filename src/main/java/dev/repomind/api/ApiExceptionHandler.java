package dev.repomind.api;

import dev.repomind.ingest.IngestService.JobAlreadyRunningException;
import dev.repomind.qa.ModelUnavailableException;
import dev.repomind.review.GitHubUnavailableException;
import dev.repomind.review.PullRequestNotFoundException;
import dev.repomind.search.NotIndexedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

	@ExceptionHandler(JobAlreadyRunningException.class)
	ProblemDetail conflict(JobAlreadyRunningException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setProperty("jobId", ex.job().id());
		problem.setProperty("jobStatus", ex.job().status());
		return problem;
	}

}
