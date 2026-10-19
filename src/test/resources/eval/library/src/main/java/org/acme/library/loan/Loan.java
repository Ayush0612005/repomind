package org.acme.library.loan;

import java.time.LocalDate;
import java.util.UUID;

public class Loan {

	private final UUID id;

	private final String isbn;

	private final UUID memberId;

	private final LocalDate borrowedOn;

	private LocalDate dueOn;

	private LocalDate returnedOn;

	private int renewals;

	private boolean reminderSent;

	public Loan(UUID id, String isbn, UUID memberId, LocalDate borrowedOn, LocalDate dueOn) {
		this.id = id;
		this.isbn = isbn;
		this.memberId = memberId;
		this.borrowedOn = borrowedOn;
		this.dueOn = dueOn;
	}

	public boolean isActive() {
		return returnedOn == null;
	}

	public boolean isOverdue(LocalDate today) {
		return isActive() && today.isAfter(dueOn);
	}

	void markReturned(LocalDate date) {
		this.returnedOn = date;
	}

	void extendTo(LocalDate newDueDate) {
		this.dueOn = newDueDate;
		this.renewals++;
		this.reminderSent = false;
	}

	void markReminderSent() {
		this.reminderSent = true;
	}

	public UUID id() {
		return id;
	}

	public String isbn() {
		return isbn;
	}

	public UUID memberId() {
		return memberId;
	}

	public LocalDate borrowedOn() {
		return borrowedOn;
	}

	public LocalDate dueOn() {
		return dueOn;
	}

	public LocalDate returnedOn() {
		return returnedOn;
	}

	public int renewals() {
		return renewals;
	}

	public boolean reminderSent() {
		return reminderSent;
	}

}
