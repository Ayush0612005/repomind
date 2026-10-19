package dev.repomind.limits;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.OptionalLong;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Model tokens per client per UTC day, counted in Redis. The budget is checked before a model call
 * and charged after it with the real usage, so one call can overshoot the budget by its own size;
 * that keeps the check to a single read instead of estimating a call's cost up front.
 */
@Component
public class TokenBudget {

	private static final Logger log = LoggerFactory.getLogger(TokenBudget.class);

	private final StringRedisTemplate redis;

	private final MeterRegistry meters;

	private final Clock clock;

	@Autowired
	public TokenBudget(StringRedisTemplate redis, MeterRegistry meters) {
		this(redis, meters, Clock.systemUTC());
	}

	TokenBudget(StringRedisTemplate redis, MeterRegistry meters, Clock clock) {
		this.redis = redis;
		this.meters = meters;
		this.clock = clock;
	}

	/**
	 * Tokens left today (negative once a call has overshot), or empty when unknown because Redis is
	 * unavailable.
	 */
	public OptionalLong remaining(ApiClient client) {
		if (client.unlimited()) {
			return OptionalLong.of(Long.MAX_VALUE);
		}
		try {
			String used = redis.opsForValue().get(key(client));
			return OptionalLong.of(client.policy().dailyTokens() - (used == null ? 0 : Long.parseLong(used)));
		}
		catch (RuntimeException ex) {
			meters.counter("repomind.limits.errors", "operation", "budget").increment();
			log.warn("Token budget unavailable for {}: {}", client.id(), ex.getMessage());
			return OptionalLong.empty();
		}
	}

	/** The guard to hand to model-calling code for this client. */
	public ModelBudget forClient(ApiClient client) {
		if (client.unlimited()) {
			return ModelBudget.UNLIMITED;
		}
		return new ModelBudget() {

			@Override
			public void reserve() {
				OptionalLong left = remaining(client);
				// unknown means Redis is down: fail open, as the rate limiter does
				if (left.isPresent() && left.getAsLong() <= 0) {
					meters.counter("repomind.limits.rejected", "reason", "budget").increment();
					throw new BudgetExceededException(client.policy().dailyTokens(), secondsUntilReset());
				}
			}

			@Override
			public void charge(long tokens) {
				if (tokens <= 0) {
					return;
				}
				meters.counter("repomind.limits.tokens", "client", client.id().startsWith("anonymous:") ? "anonymous"
						: client.id()).increment(tokens);
				try {
					String key = key(client);
					redis.opsForValue().increment(key, tokens);
					// two days, so the counter outlives the whole UTC day it belongs to
					redis.expire(key, Duration.ofDays(2));
				}
				catch (RuntimeException ex) {
					meters.counter("repomind.limits.errors", "operation", "budget").increment();
					log.warn("Could not record {} tokens for {}: {}", tokens, client.id(), ex.getMessage());
				}
			}

		};
	}

	long secondsUntilReset() {
		ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
		ZonedDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
		return Math.max(1, Duration.between(now, midnight).toSeconds());
	}

	private String key(ApiClient client) {
		return "repomind:budget:" + client.id() + ":" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
	}

}
