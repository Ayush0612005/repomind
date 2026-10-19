package dev.repomind.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import dev.repomind.qa.ModelUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class ChatGatewayTest {

	private static final Prompt PROMPT = new Prompt("question");

	/** Named like the real providers so the gateway derives "anthropic" / "ollama". */
	static class AnthropicChatModel extends FakeModel {

		AnthropicChatModel(Supplier<String> reply) {
			super(reply);
		}

	}

	static class OllamaChatModel extends FakeModel {

		OllamaChatModel(Supplier<String> reply) {
			super(reply);
		}

	}

	abstract static class FakeModel implements ChatModel {

		final AtomicInteger calls = new AtomicInteger();

		private final Supplier<String> reply;

		FakeModel(Supplier<String> reply) {
			this.reply = reply;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			calls.incrementAndGet();
			return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.get()))));
		}

	}

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private static Supplier<String> failing(String message) {
		return () -> {
			throw new IllegalStateException(message);
		};
	}

	private ChatGateway gateway(Duration openFor, ChatModel... models) {
		return new ChatGateway(List.of(models), new ChatProperties(List.of("anthropic", "ollama"),
				new ChatProperties.CircuitBreaker(50, 4, 4, openFor, 1)), meters);
	}

	private static String text(ChatResponse response) {
		return response.getResult().getOutput().getText();
	}

	@Test
	void usesTheConfiguredOrderNotTheBeanOrder() {
		var ollama = new OllamaChatModel(() -> "from ollama");
		var claude = new AnthropicChatModel(() -> "from claude");
		ChatGateway gateway = gateway(Duration.ofSeconds(30), ollama, claude);

		assertThat(gateway.providers()).extracting(ChatGateway.Provider::name).containsExactly("anthropic", "ollama");
		assertThat(text(gateway.call(PROMPT))).isEqualTo("from claude");
		assertThat(ollama.calls).hasValue(0);
	}

	@Test
	void fallsBackWhenThePrimaryFails() {
		var claude = new AnthropicChatModel(failing("529 overloaded"));
		var ollama = new OllamaChatModel(() -> "from ollama");
		ChatGateway gateway = gateway(Duration.ofSeconds(30), claude, ollama);

		assertThat(text(gateway.call(PROMPT))).isEqualTo("from ollama");
		assertThat(meters.counter("repomind.chat.calls", "provider", "ollama", "outcome", "fallback").count())
			.isEqualTo(1);
	}

	@Test
	void openCircuitSkipsTheFailingProviderUntilItRecovers() throws InterruptedException {
		AtomicInteger outage = new AtomicInteger(1);
		var claude = new AnthropicChatModel(() -> {
			if (outage.get() == 1) {
				throw new IllegalStateException("connection refused");
			}
			return "from claude";
		});
		var ollama = new OllamaChatModel(() -> "from ollama");
		ChatGateway gateway = gateway(Duration.ofMillis(200), claude, ollama);

		for (int i = 0; i < 4; i++) {
			gateway.call(PROMPT);
		}
		CircuitBreaker breaker = gateway.providers().getFirst().breaker();
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		// while open, Claude is not even tried: no timeout is paid before falling back
		for (int i = 0; i < 5; i++) {
			assertThat(text(gateway.call(PROMPT))).isEqualTo("from ollama");
		}
		assertThat(claude.calls).hasValue(4);
		assertThat(meters.counter("repomind.chat.calls", "provider", "anthropic", "outcome", "skipped").count())
			.isEqualTo(5);

		// after the open period one probe goes through; it succeeds, so the circuit closes
		outage.set(0);
		Thread.sleep(300);
		assertThat(text(gateway.call(PROMPT))).isEqualTo("from claude");
		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void reportsEveryProvidersFailureWhenAllFail() {
		ChatGateway gateway = gateway(Duration.ofSeconds(30), new AnthropicChatModel(failing("401 invalid x-api-key")),
				new OllamaChatModel(failing("connection refused")));

		assertThatThrownBy(() -> gateway.call(PROMPT)).isInstanceOf(ModelUnavailableException.class)
			.hasMessageContaining("anthropic: 401 invalid x-api-key")
			.hasMessageContaining("ollama: connection refused");
		assertThatThrownBy(() -> gateway(Duration.ofSeconds(30)).call(PROMPT))
			.isInstanceOf(ModelUnavailableException.class)
			.hasMessageContaining("No chat model");
	}

	@Test
	void namesProvidersAfterTheirModelClass() {
		assertThat(ChatGateway.providerName(new AnthropicChatModel(() -> ""))).isEqualTo("anthropic");
		assertThat(ChatGateway.providerName(new FakeModel(() -> "") {
		})).isNotBlank();
	}

}
