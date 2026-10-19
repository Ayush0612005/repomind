package dev.repomind.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

	private static final byte[] PAYLOAD = "Hello, World!".getBytes(StandardCharsets.UTF_8);

	@Test
	void matchesGitHubsDocumentedTestVector() {
		// from GitHub's "Validating webhook deliveries" documentation
		assertThat(WebhookSignature.sign("It's a Secret to Everybody", PAYLOAD))
			.isEqualTo("sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
	}

	@Test
	void rejectsMissingWrongOrTamperedSignatures() {
		String secret = "It's a Secret to Everybody";
		String valid = WebhookSignature.sign(secret, PAYLOAD);

		assertThat(WebhookSignature.isValid(secret, PAYLOAD, valid)).isTrue();
		assertThat(WebhookSignature.isValid(secret, PAYLOAD, null)).isFalse();
		assertThat(WebhookSignature.isValid(secret, PAYLOAD, valid.substring("sha256=".length()))).isFalse();
		assertThat(WebhookSignature.isValid("other secret", PAYLOAD, valid)).isFalse();
		assertThat(WebhookSignature.isValid(secret, "Hello, World?".getBytes(StandardCharsets.UTF_8), valid))
			.isFalse();
	}

}
