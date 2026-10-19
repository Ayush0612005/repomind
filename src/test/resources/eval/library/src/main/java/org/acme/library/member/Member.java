package org.acme.library.member;

import java.math.BigDecimal;
import java.util.UUID;

public class Member {

	private final UUID id;

	private final String name;

	private final String email;

	private MembershipTier tier;

	private boolean suspended;

	private BigDecimal outstandingFines = BigDecimal.ZERO;

	public Member(UUID id, String name, String email, MembershipTier tier) {
		this.id = id;
		this.name = name;
		this.email = email;
		this.tier = tier;
	}

	public void addFine(BigDecimal amount) {
		outstandingFines = outstandingFines.add(amount);
	}

	public void payFines(BigDecimal amount) {
		if (amount.compareTo(outstandingFines) > 0) {
			throw new IllegalArgumentException("Payment exceeds outstanding fines");
		}
		outstandingFines = outstandingFines.subtract(amount);
	}

	public void suspend() {
		this.suspended = true;
	}

	public void reinstate() {
		this.suspended = false;
	}

	public void upgrade(MembershipTier newTier) {
		this.tier = newTier;
	}

	public UUID id() {
		return id;
	}

	public String name() {
		return name;
	}

	public String email() {
		return email;
	}

	public MembershipTier tier() {
		return tier;
	}

	public boolean isSuspended() {
		return suspended;
	}

	public BigDecimal outstandingFines() {
		return outstandingFines;
	}

}
