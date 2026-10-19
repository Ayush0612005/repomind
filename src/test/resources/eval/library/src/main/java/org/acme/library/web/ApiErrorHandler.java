package org.acme.library.web;

import org.acme.library.book.BookService.BookNotFoundException;
import org.acme.library.book.BookService.InvalidBookException;
import org.acme.library.loan.LoanExceptions.AlreadyBorrowedException;
import org.acme.library.loan.LoanExceptions.BookUnavailableException;
import org.acme.library.loan.LoanExceptions.LoanLimitExceededException;
import org.acme.library.loan.LoanExceptions.MemberSuspendedException;
import org.acme.library.loan.LoanExceptions.RenewalNotAllowedException;
import org.acme.library.member.MemberService.DuplicateEmailException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps business exceptions to HTTP status codes with RFC 9457 problem details. */
@RestControllerAdvice
public class ApiErrorHandler {

	@ExceptionHandler({ BookNotFoundException.class })
	ProblemDetail notFound(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler({ InvalidBookException.class, IllegalArgumentException.class })
	ProblemDetail badRequest(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	/** Borrowing rules that the member could satisfy later are conflicts, not client errors. */
	@ExceptionHandler({ LoanLimitExceededException.class, BookUnavailableException.class,
			AlreadyBorrowedException.class, RenewalNotAllowedException.class, DuplicateEmailException.class })
	ProblemDetail conflict(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler(MemberSuspendedException.class)
	ProblemDetail suspended(MemberSuspendedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setProperty("action", "pay outstanding fines to restore borrowing");
		return problem;
	}

}
