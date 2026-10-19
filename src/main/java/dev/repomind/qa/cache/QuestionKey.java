package dev.repomind.qa.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identity of a question for the exact-match tier. Normalisation removes differences that cannot
 * change the answer (case, spacing, trailing punctuation) and nothing else; anything subtler is the
 * semantic tier's job.
 */
public final class QuestionKey {

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s?.!]+$");

	private QuestionKey() {
	}

	public static String normalize(String question) {
		String text = WHITESPACE.matcher(question.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
		return TRAILING_PUNCTUATION.matcher(text).replaceAll("");
	}

	/** SHA-256 of the normalised question, scoped to the index generation it was answered from. */
	public static String of(UUID generationJobId, String question) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			sha.update(generationJobId.toString().getBytes(StandardCharsets.UTF_8));
			sha.update((byte) 0);
			sha.update(normalize(question).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(sha.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
