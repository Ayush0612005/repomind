package dev.repomind.review;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies GitHub's {@code X-Hub-Signature-256} header: {@code sha256=} followed by the hex
 * HMAC-SHA256 of the raw request body, keyed with the webhook secret.
 */
public final class WebhookSignature {

	private static final String PREFIX = "sha256=";

	private WebhookSignature() {
	}

	public static String sign(String secret, byte[] payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return PREFIX + HexFormat.of().formatHex(mac.doFinal(payload));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 is unavailable", ex);
		}
	}

	/** Constant-time comparison, so response timing reveals nothing about the expected signature. */
	public static boolean isValid(String secret, byte[] payload, String header) {
		if (header == null || !header.startsWith(PREFIX)) {
			return false;
		}
		byte[] expected = sign(secret, payload).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.US_ASCII));
	}

}
