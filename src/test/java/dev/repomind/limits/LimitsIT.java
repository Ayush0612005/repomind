package dev.repomind.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.nio.charset.StandardCharsets;

import dev.repomind.repo.Repo;
import dev.repomind.review.WebhookSignature;
import dev.repomind.support.IntegrationTest;
import dev.repomind.support.ScriptedChatModel;
import dev.repomind.support.TestIndexer;
import dev.repomind.support.UnreachableRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** API keys, rate limits and token budgets against real Redis (clients configured in application-test.yaml). */
@IntegrationTest
class LimitsIT {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	TestIndexer indexer;

	@Autowired
	ScriptedChatModel model;

	@Test
	void rateLimitRejectsTheRequestAfterTheBurstWithRetryAfter() {
		for (int i = 0; i < 3; i++) {
			assertThat(get("/api/repos", "rate-test-key")).hasStatusOk()
				.headers()
				.hasValue("X-RateLimit-Limit", "3")
				.hasValue("X-RateLimit-Remaining", String.valueOf(2 - i));
		}

		MvcTestResult rejected = get("/api/repos", "rate-test-key");

		assertThat(rejected).hasStatus(HttpStatus.TOO_MANY_REQUESTS).headers().containsHeader("Retry-After");
		assertThat(Long.parseLong(rejected.getResponse().getHeader("Retry-After"))).isBetween(1L, 20L);
		assertThat(rejected).bodyJson().extractingPath("$.limit").isEqualTo("rate-limit");
		// other callers have their own buckets
		assertThat(get("/api/repos", null)).hasStatusOk();
	}

	@Test
	void unknownKeysAreRejectedAndBearerTokensAreAccepted() {
		assertThat(get("/api/repos", "not-a-real-key")).hasStatus(HttpStatus.UNAUTHORIZED)
			.headers()
			.containsHeader("WWW-Authenticate");
		assertThat(mvc.get().uri("/api/repos").header("Authorization", "Bearer budget-test-key")).hasStatusOk();
	}

	@Test
	void indexingAndReviewEndpointsRequireAKeyWhileReadsStayOpen() {
		String body = "{\"repository\": \"acme/demo\"}";

		assertThat(mvc.post().uri("/api/repos").contentType(MediaType.APPLICATION_JSON).content(body))
			.hasStatus(HttpStatus.UNAUTHORIZED)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("requires an API key");
		assertThat(mvc.post().uri("/api/repos/{id}/pulls/1/reviews", java.util.UUID.randomUUID()))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		// reads remain available to anonymous visitors of a public demo
		assertThat(get("/api/repos", null)).hasStatusOk();
		// with a key the guard passes and the request reaches the controller (404: no such repository)
		assertThat(mvc.post()
			.uri("/api/repos/{id}/reindex", java.util.UUID.randomUUID())
			.header("X-Api-Key", "admin-test-key")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void exhaustedBudgetBlocksModelCallsButStillServesCachedAnswers() {
		model.reset();
		Repo repo = indexer.index("budget", TestIndexer.PETCLINIC);

		// the budget is 1 token: the first call is allowed and overshoots it
		MvcTestResult first = ask(repo, "How is the vet list paged?");
		assertThat(first).hasStatusOk().headers().hasValue("X-Token-Budget-Remaining", "0");

		MvcTestResult refused = ask(repo, "Where are owners looked up?");
		assertThat(refused).hasStatus(HttpStatus.TOO_MANY_REQUESTS).headers().containsHeader("Retry-After");
		assertThat(refused).bodyJson().extractingPath("$.limit").isEqualTo("token-budget");

		// a repeat costs no tokens, so it is served from the cache
		assertThat(ask(repo, "How is the vet list paged?")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.cache.status")
			.isEqualTo("EXACT");
		assertThat(model.prompts()).hasSize(1);
	}

	@Test
	void githubWebhooksBypassApiKeysAndLimits() {
		byte[] ping = "{\"zen\": \"Design for failure.\"}".getBytes(StandardCharsets.UTF_8);

		assertThat(mvc.post()
			.uri("/api/github/webhook")
			.header("X-GitHub-Event", "ping")
			.header("X-Api-Key", "not-a-real-key")
			.header("X-Hub-Signature-256", WebhookSignature.sign("test-webhook-secret", ping))
			.contentType(MediaType.APPLICATION_JSON)
			.content(ping)).hasStatusOk();
	}

	@Test
	void redisOutageFailsOpenRatherThanTakingTheApiDown() {
		ApiClient client = new ApiClient("outage-test", new LimitsProperties.Policy(1, 1));
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		try (UnreachableRedis redis = new UnreachableRedis()) {
			RateLimiter limiter = new RateLimiter(redis.template(), meters);
			TokenBudget budget = new TokenBudget(redis.template(), meters);

			assertThat(limiter.acquire(client).allowed()).isTrue();
			assertThat(limiter.acquire(client).allowed()).isTrue();
			assertThatNoException().isThrownBy(() -> budget.forClient(client).reserve());
			assertThat(budget.remaining(client)).isEmpty();
			assertThat(meters.find("repomind.limits.errors").counters()).isNotEmpty();
		}
	}

	private MvcTestResult get(String uri, String apiKey) {
		var request = mvc.get().uri(uri);
		if (apiKey != null) {
			request.header("X-Api-Key", apiKey);
		}
		return request.exchange();
	}

	private MvcTestResult ask(Repo repo, String question) {
		return mvc.post()
			.uri("/api/repos/{id}/ask", repo.id())
			.header("X-Api-Key", "budget-test-key")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"question\": \"" + question + "\"}")
			.exchange();
	}

}
