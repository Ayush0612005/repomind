package org.acme.library.loan;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.acme.library.book.Book;
import org.acme.library.book.BookService;
import org.acme.library.config.LibraryProperties;
import org.acme.library.loan.LoanExceptions.AlreadyBorrowedException;
import org.acme.library.loan.LoanExceptions.BookUnavailableException;
import org.acme.library.loan.LoanExceptions.LoanLimitExceededException;
import org.acme.library.loan.LoanExceptions.MemberSuspendedException;
import org.acme.library.loan.LoanExceptions.RenewalNotAllowedException;
import org.acme.library.member.Member;
import org.acme.library.member.MemberRepository;
import org.acme.library.member.MemberService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanService {

	private final LoanRepository loans;

	private final BookService books;

	private final MemberRepository members;

	private final MemberService memberService;

	private final FineCalculator fines;

	private final LibraryProperties properties;

	private final Clock clock;

	public LoanService(LoanRepository loans, BookService books, MemberRepository members, MemberService memberService,
			FineCalculator fines, LibraryProperties properties, Clock clock) {
		this.loans = loans;
		this.books = books;
		this.members = members;
		this.memberService = memberService;
		this.fines = fines;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Lends a copy of the book to the member. Checks, in order: the member is not suspended, is under
	 * their tier's active-loan limit, does not already hold this title, and a copy is available.
	 */
	@Transactional
	public Loan checkout(UUID memberId, String isbn) {
		Member member = members.findById(memberId).orElseThrow(() -> new IllegalArgumentException("No member " + memberId));
		if (member.isSuspended()) {
			throw new MemberSuspendedException();
		}
		int limit = member.tier().maxActiveLoans();
		if (loans.countActiveByMember(memberId) >= limit) {
			throw new LoanLimitExceededException(limit);
		}
		Book book = books.get(isbn);
		if (loans.existsActiveByMemberAndIsbn(memberId, book.isbn())) {
			throw new AlreadyBorrowedException(book.isbn());
		}
		if (!book.isAvailable()) {
			throw new BookUnavailableException(book.isbn());
		}
		book.checkOutCopy();
		LocalDate today = LocalDate.now(clock);
		return loans.save(new Loan(UUID.randomUUID(), book.isbn(), memberId, today,
				today.plusDays(properties.loanPeriod().toDays())));
	}

	/** Returns the copy, charging a late fine (if any) to the member's account. */
	@Transactional
	public BigDecimal returnBook(UUID loanId) {
		Loan loan = loans.findById(loanId).orElseThrow(() -> new IllegalArgumentException("No loan " + loanId));
		if (!loan.isActive()) {
			throw new IllegalStateException("Loan " + loanId + " was already returned");
		}
		LocalDate today = LocalDate.now(clock);
		loan.markReturned(today);
		books.get(loan.isbn()).returnCopy();
		loans.save(loan);

		Member member = members.findById(loan.memberId()).orElseThrow();
		BigDecimal fine = fines.calculate(loan.dueOn(), today, member.tier());
		if (fine.signum() > 0) {
			memberService.chargeFine(member, fine);
		}
		return fine;
	}

	/** Extends the due date by one loan period, up to the configured number of renewals; overdue loans cannot be renewed. */
	@Transactional
	public Loan renew(UUID loanId) {
		Loan loan = loans.findById(loanId).orElseThrow(() -> new IllegalArgumentException("No loan " + loanId));
		LocalDate today = LocalDate.now(clock);
		if (loan.isOverdue(today)) {
			throw new RenewalNotAllowedException("loan is overdue");
		}
		if (loan.renewals() >= properties.maxRenewals()) {
			throw new RenewalNotAllowedException("renewal limit of " + properties.maxRenewals() + " reached");
		}
		loan.extendTo(loan.dueOn().plusDays(properties.loanPeriod().toDays()));
		return loans.save(loan);
	}

}
