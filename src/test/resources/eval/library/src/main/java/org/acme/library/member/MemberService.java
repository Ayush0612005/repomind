package org.acme.library.member;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.acme.library.config.LibraryProperties;
import org.springframework.stereotype.Service;

@Service
public class MemberService {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$", Pattern.CASE_INSENSITIVE);

	private final MemberRepository members;

	private final LibraryProperties properties;

	public MemberService(MemberRepository members, LibraryProperties properties) {
		this.members = members;
		this.properties = properties;
	}

	/** Registers a member on the standard tier. Emails are unique, compared case-insensitively. */
	public Member register(String name, String email) {
		String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
		if (!EMAIL.matcher(normalizedEmail).matches()) {
			throw new IllegalArgumentException("Invalid email address: " + email);
		}
		if (members.existsByEmail(normalizedEmail)) {
			throw new DuplicateEmailException(normalizedEmail);
		}
		return members.save(new Member(UUID.randomUUID(), name.strip(), normalizedEmail, MembershipTier.STANDARD));
	}

	/**
	 * Records a fine and suspends the member once unpaid fines reach the configured threshold, which
	 * blocks further borrowing until they pay.
	 */
	public void chargeFine(Member member, BigDecimal amount) {
		member.addFine(amount);
		if (member.outstandingFines().compareTo(properties.suspensionThreshold()) >= 0) {
			member.suspend();
		}
		members.save(member);
	}

	/** Applies a payment and lifts the suspension when the balance drops below the threshold. */
	public void payFines(Member member, BigDecimal amount) {
		member.payFines(amount);
		if (member.isSuspended() && member.outstandingFines().compareTo(properties.suspensionThreshold()) < 0) {
			member.reinstate();
		}
		members.save(member);
	}

	public static class DuplicateEmailException extends RuntimeException {

		public DuplicateEmailException(String email) {
			super("A member with email " + email + " already exists");
		}

	}

}
