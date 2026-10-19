package org.acme.library.loan;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository {

	Optional<Loan> findById(UUID id);

	int countActiveByMember(UUID memberId);

	boolean existsActiveByMemberAndIsbn(UUID memberId, String isbn);

	/** Active loans due on or before {@code date} that have not been reminded yet. */
	List<Loan> findDueForReminder(LocalDate date);

	Loan save(Loan loan);

}
