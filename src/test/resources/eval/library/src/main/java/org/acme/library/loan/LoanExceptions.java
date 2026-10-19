package org.acme.library.loan;

/** Business rule violations raised when borrowing, returning or renewing. */
public final class LoanExceptions {

	private LoanExceptions() {
	}

	public static class LoanLimitExceededException extends RuntimeException {

		public LoanLimitExceededException(int limit) {
			super("Loan limit of " + limit + " active loans reached");
		}

	}

	public static class BookUnavailableException extends RuntimeException {

		public BookUnavailableException(String isbn) {
			super("All copies of " + isbn + " are on loan");
		}

	}

	public static class MemberSuspendedException extends RuntimeException {

		public MemberSuspendedException() {
			super("Member is suspended until outstanding fines are paid");
		}

	}

	public static class AlreadyBorrowedException extends RuntimeException {

		public AlreadyBorrowedException(String isbn) {
			super("Member already has a copy of " + isbn);
		}

	}

	public static class RenewalNotAllowedException extends RuntimeException {

		public RenewalNotAllowedException(String reason) {
			super("Cannot renew: " + reason);
		}

	}

}
