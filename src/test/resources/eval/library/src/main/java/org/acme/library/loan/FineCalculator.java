package org.acme.library.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.acme.library.config.LibraryProperties;
import org.acme.library.member.MembershipTier;
import org.springframework.stereotype.Component;

/**
 * Late-return fines: a flat amount per overdue day, starting after the member tier's grace
 * period, capped per loan so a lost-then-found book does not produce an absurd fine.
 */
@Component
public class FineCalculator {

	private final LibraryProperties properties;

	public FineCalculator(LibraryProperties properties) {
		this.properties = properties;
	}

	public BigDecimal calculate(LocalDate dueOn, LocalDate returnedOn, MembershipTier tier) {
		long daysLate = ChronoUnit.DAYS.between(dueOn, returnedOn) - tier.graceDays();
		if (daysLate <= 0) {
			return BigDecimal.ZERO;
		}
		BigDecimal fine = properties.finePerDay().multiply(BigDecimal.valueOf(daysLate));
		return fine.min(properties.maxFinePerLoan()).setScale(2, RoundingMode.HALF_UP);
	}

}
