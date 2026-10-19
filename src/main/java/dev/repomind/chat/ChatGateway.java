package dev.repomind.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import dev.repomind.qa.ModelUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Calls the configured chat models in preference order (Claude, then the local Ollama model by
 * default), moving to the next provider when one fails. Each provider has a circuit breaker: once
 * a provider keeps failing it is skipped outright for a while, so requests stop paying its timeout
 * before falling back, and it is probed again later to detect recovery.
 */
@Component
public class ChatGateway {

	private static final Logger log = LoggerFactory.getLogger(ChatGateway.class);

	/** A chat model with its circuit breaker. */
	public record Provider(String name, ChatModel model, CircuitBreaker breaker) {
	}

	private final List<Provider> providers;

	private final MeterRegistry meters;

	@Autowired
	public ChatGateway(ObjectProvider<ChatModel> models, ChatProperties properties, MeterRegistry meters) {
		this(models.orderedStream().toList(), properties, meters);
	}

	ChatGateway(List<ChatModel> models, ChatProperties properties, MeterRegistry meters) {
		this.meters = meters;
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
			.failureRateThreshold(properties.circuitBreaker().failureRateThreshold())
			.slidingWindowSize(properties.circuitBreaker().slidingWindowSize())
			.minimumNumberOfCalls(properties.circuitBreaker().minimumCalls())
			.waitDurationInOpenState(properties.circuitBreaker().openFor())
			.permittedNumberOfCallsInHalfOpenState(properties.circuitBreaker().halfOpenCalls())
			.build();
		List<String> order = properties.order().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
		this.providers = models.stream()
			.map(m -> new Provider(providerName(m), m, CircuitBreaker.of(providerName(m), config)))
			.sorted(Comparator.comparingInt(p -> {
				int index = order.indexOf(p.name());
				return index < 0 ? Integer.MAX_VALUE : index;
			}))
			.toList();
		for (Provider p : providers) {
			// 0 closed, 1 open, 2 half-open, as in Resilience4j's own metrics
			Gauge.builder("repomind.chat.circuit.state", p.breaker(), b -> switch (b.getState()) {
				case OPEN, FORCED_OPEN -> 1;
				case HALF_OPEN -> 2;
				default -> 0;
			}).tag("provider", p.name()).register(meters);
			p.breaker()
				.getEventPublisher()
				.onStateTransition(e -> log.warn("Chat provider {} circuit {}", p.name(),
						e.getStateTransition().getToState()));
		}
		log.info("Chat providers in order: {}", providers.stream().map(Provider::name).toList());
	}

	public List<Provider> providers() {
		return providers;
	}

	/**
	 * @throws ModelUnavailableException when no provider is configured, every provider's circuit is
	 * open, or every provider that was tried failed
	 */
	public ChatResponse call(Prompt prompt) {
		if (providers.isEmpty()) {
			throw new ModelUnavailableException("No chat model is configured", null);
		}
		List<String> failures = new ArrayList<>();
		RuntimeException last = null;
		for (int i = 0; i < providers.size(); i++) {
			Provider provider = providers.get(i);
			if (!provider.breaker().tryAcquirePermission()) {
				failures.add(provider.name() + ": circuit open");
				count(provider, "skipped");
				continue;
			}
			long start = System.nanoTime();
			try {
				ChatResponse response = provider.model().call(prompt);
				provider.breaker().onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
				count(provider, i == 0 ? "success" : "fallback");
				return response;
			}
			catch (RuntimeException ex) {
				provider.breaker().onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, ex);
				count(provider, "failure");
				failures.add(provider.name() + ": " + ex.getMessage());
				last = ex;
				log.warn("Chat provider {} failed{}: {}", provider.name(),
						i + 1 < providers.size() ? ", trying the next one" : "", ex.getMessage());
			}
		}
		throw new ModelUnavailableException("The language model is unavailable (" + String.join("; ", failures) + ")",
				last);
	}

	private void count(Provider provider, String outcome) {
		meters.counter("repomind.chat.calls", "provider", provider.name(), "outcome", outcome).increment();
	}

	/** {@code AnthropicChatModel} → {@code anthropic}; other models by their class name. */
	static String providerName(ChatModel model) {
		String name = model.getClass().getSimpleName();
		if (name.isEmpty()) {
			name = model.getClass().getName(); // anonymous class
		}
		if (name.endsWith("ChatModel") && name.length() > "ChatModel".length()) {
			name = name.substring(0, name.length() - "ChatModel".length());
		}
		return name.toLowerCase(Locale.ROOT);
	}

}
