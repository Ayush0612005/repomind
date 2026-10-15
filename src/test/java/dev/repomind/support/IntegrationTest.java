package dev.repomind.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import dev.repomind.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context against Testcontainers Postgres/Kafka with fake AI models. Every
 * integration test uses the same configuration so Spring caches and shares one context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, FakeAiConfiguration.class })
public @interface IntegrationTest {
}
