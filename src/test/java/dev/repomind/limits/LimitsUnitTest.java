package dev.repomind.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class LimitsUnitTest {

	private static final LimitsProperties.Client CLIENT = new LimitsProperties.Client("ci-bot",
			ApiClients.sha256("s3cret-key"), 60, 1_000);

	@Test
	void resolvesKeysByHashAndAnonymousCallersByAddress() {
		ApiClients clients = new ApiClients(new LimitsProperties(true, null, List.of(CLIENT)));

		assertThat(clients.forKey("s3cret-key")).hasValueSatisfying(c -> {
			assertThat(c.id()).isEqualTo("ci-bot");
			assertThat(c.policy().dailyTokens()).isEqualTo(1_000);
		});
		assertThat(clients.forKey("s3cret-kez")).isEmpty();
		assertThat(clients.anonymous("10.0.0.7")).as("no anonymous policy configured").isEmpty();

		ApiClients open = new ApiClients(
				new LimitsProperties(true, new LimitsProperties.Policy(10, 500), List.of()));
		assertThat(open.anonymous("10.0.0.7")).hasValueSatisfying(c -> assertThat(c.id()).isEqualTo("anonymous:10.0.0.7"));
	}

	@Test
	void rejectsClientsConfiguredWithAPlainKeyInsteadOfItsHash() {
		assertThatThrownBy(() -> new ApiClients(new LimitsProperties(true, null,
				List.of(new LimitsProperties.Client("oops", "s3cret-key", 60, 1_000)))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("key-sha256");
	}

	@Test
	void prefersReportedUsageAndEstimatesWhenTheProviderReportsNone() {
		ChatResponse reported = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1_200, 300)).build());
		ChatResponse silent = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));

		assertThat(TokenUsage.of(reported, "x".repeat(40), "y")).isEqualTo(1_500);
		assertThat(TokenUsage.of(silent, "x".repeat(400), "y".repeat(100))).isEqualTo(125);
	}

	@Test
	void budgetsResetAtMidnightUtc() {
		Clock clock = Clock.fixed(Instant.parse("2026-10-19T23:59:00Z"), ZoneOffset.UTC);
		TokenBudget budget = new TokenBudget(null, new SimpleMeterRegistry(), clock);

		assertThat(budget.secondsUntilReset()).isEqualTo(60);
		assertThat(budget.forClient(ApiClient.UNLIMITED)).isSameAs(ModelBudget.UNLIMITED);
	}

}
