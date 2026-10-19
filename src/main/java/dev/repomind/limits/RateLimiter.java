package dev.repomind.limits;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiting in Redis. The whole read-refill-take step runs as one Lua script, so
 * concurrent requests (from several app instances, too) cannot both spend the last token, and the
 * clock is Redis's own, so instances with skewed clocks agree.
 */
@Component
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	/**
	 * KEYS[1] bucket; ARGV[1] capacity; ARGV[2] refill per millisecond.
	 * Returns {allowed (1/0), tokens left, milliseconds until a token is available}.
	 */
	private static final RedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
			local capacity = tonumber(ARGV[1])
			local refill = tonumber(ARGV[2])
			local time = redis.call('TIME')
			local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
			local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
			local tokens = tonumber(state[1]) or capacity
			local ts = tonumber(state[2]) or now
			tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill)
			local allowed, wait = 0, 0
			if tokens >= 1 then
			  tokens = tokens - 1
			  allowed = 1
			else
			  wait = math.ceil((1 - tokens) / refill)
			end
			redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
			redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill) + 1000)
			return {allowed, math.floor(tokens), wait}
			""", List.class);

	/** @param retryAfterSeconds 0 when allowed */
	public record Decision(boolean allowed, int limit, long remaining, long retryAfterSeconds) {
	}

	private final StringRedisTemplate redis;

	private final MeterRegistry meters;

	public RateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
		this.redis = redis;
		this.meters = meters;
	}

	/** Takes one request from the client's bucket. Fails open if Redis is unavailable. */
	public Decision acquire(ApiClient client) {
		int perMinute = client.policy().requestsPerMinute();
		try {
			List<?> result = redis.execute(TOKEN_BUCKET, List.of("repomind:rate:" + client.id()),
					String.valueOf(perMinute), String.valueOf(perMinute / 60_000.0));
			boolean allowed = ((Number) result.get(0)).longValue() == 1;
			long remaining = ((Number) result.get(1)).longValue();
			long waitMs = ((Number) result.get(2)).longValue();
			if (!allowed) {
				meters.counter("repomind.limits.rejected", "reason", "rate").increment();
			}
			return new Decision(allowed, perMinute, remaining, allowed ? 0 : Math.max(1, (waitMs + 999) / 1000));
		}
		catch (RuntimeException ex) {
			// availability over strictness: an outage of the limiter must not take the API down
			meters.counter("repomind.limits.errors", "operation", "rate").increment();
			log.warn("Rate limiter unavailable, allowing request from {}: {}", client.id(), ex.getMessage());
			return new Decision(true, perMinute, -1, 0);
		}
	}

}
