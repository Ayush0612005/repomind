package org.acme.library.book;

/** Validates ISBN-13 numbers, accepting hyphens and spaces between digit groups. */
public final class IsbnValidator {

	private IsbnValidator() {
	}

	public static String normalize(String isbn) {
		return isbn == null ? "" : isbn.replace("-", "").replace(" ", "");
	}

	/**
	 * ISBN-13 checksum: digits are weighted alternately 1 and 3, and the weighted sum including the
	 * check digit must be divisible by 10. Only 978/979 prefixes are valid for books.
	 */
	public static boolean isValid(String isbn) {
		String digits = normalize(isbn);
		if (digits.length() != 13 || !digits.chars().allMatch(Character::isDigit)) {
			return false;
		}
		if (!digits.startsWith("978") && !digits.startsWith("979")) {
			return false;
		}
		int sum = 0;
		for (int i = 0; i < 13; i++) {
			int digit = digits.charAt(i) - '0';
			sum += (i % 2 == 0) ? digit : digit * 3;
		}
		return sum % 10 == 0;
	}

}
