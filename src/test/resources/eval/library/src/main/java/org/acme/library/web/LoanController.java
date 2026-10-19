package org.acme.library.web;

import java.math.BigDecimal;
import java.util.UUID;

import org.acme.library.loan.Loan;
import org.acme.library.loan.LoanService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

	public record CheckoutRequest(UUID memberId, String isbn) {
	}

	public record ReturnResponse(UUID loanId, BigDecimal fineCharged) {
	}

	private final LoanService loans;

	public LoanController(LoanService loans) {
		this.loans = loans;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Loan checkout(@RequestBody CheckoutRequest request) {
		return loans.checkout(request.memberId(), request.isbn());
	}

	@PostMapping("/{id}/return")
	ReturnResponse returnBook(@PathVariable UUID id) {
		return new ReturnResponse(id, loans.returnBook(id));
	}

	@PostMapping("/{id}/renew")
	Loan renew(@PathVariable UUID id) {
		return loans.renew(id);
	}

}
