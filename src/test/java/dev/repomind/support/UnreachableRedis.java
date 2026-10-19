package dev.repomind.support;

import java.time.Duration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** A Redis client pointed at a closed port, for testing that Redis outages degrade gracefully. */
public final class UnreachableRedis implements AutoCloseable {

	private final LettuceConnectionFactory factory;

	public UnreachableRedis() {
		factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1),
				LettuceClientConfiguration.builder()
					.commandTimeout(Duration.ofMillis(300))
					.clientOptions(ClientOptions.builder()
						.socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
						.build())
					.build());
		factory.afterPropertiesSet();
		factory.start();
	}

	public StringRedisTemplate template() {
		return new StringRedisTemplate(factory);
	}

	@Override
	public void close() {
		factory.destroy();
	}

}
