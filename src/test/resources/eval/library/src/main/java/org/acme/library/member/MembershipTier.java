package org.acme.library.member;

/** Membership levels. Premium members borrow more books and get a grace period on late returns. */
public enum MembershipTier {

	STANDARD(3, 0), PREMIUM(10, 2), STAFF(25, 7);

	private final int maxActiveLoans;

	private final int graceDays;

	MembershipTier(int maxActiveLoans, int graceDays) {
		this.maxActiveLoans = maxActiveLoans;
		this.graceDays = graceDays;
	}

	public int maxActiveLoans() {
		return maxActiveLoans;
	}

	/** Days after the due date before fines start accruing. */
	public int graceDays() {
		return graceDays;
	}

}
