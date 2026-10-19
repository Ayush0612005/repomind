package dev.repomind.support;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces model servers and GitHub with deterministic fakes so integration tests run offline. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAiConfiguration {

	@Bean
	@Primary
	HashingEmbeddingModel hashingEmbeddingModel() {
		return new HashingEmbeddingModel(768);
	}

	@Bean
	@Primary
	ScriptedChatModel scriptedChatModel() {
		return new ScriptedChatModel();
	}

	@Bean
	@Primary
	FakePullRequests fakePullRequests() {
		return new FakePullRequests();
	}

}
