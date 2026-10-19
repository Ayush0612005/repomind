package dev.repomind.limits;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** Tokens a model call used: as reported by the provider, else estimated from the text. */
public final class TokenUsage {

	private TokenUsage() {
	}

	public static long of(ChatResponse response, String prompt, String completion) {
		Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
		Integer reported = usage == null ? null : usage.getTotalTokens();
		if (reported != null && reported > 0) {
			return reported;
		}
		// some providers (and test fakes) report nothing; ~4 characters per token is the usual estimate
		return (length(prompt) + length(completion) + 3) / 4;
	}

	private static long length(String text) {
		return text == null ? 0 : text.length();
	}

}
