package dev.repomind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import dev.repomind.TestcontainersConfiguration;
import dev.repomind.chat.ChatGateway;
import dev.repomind.ingest.IngestJobDao;
import dev.repomind.ingest.IngestMessaging;
import dev.repomind.qa.Answer;
import dev.repomind.qa.AnswerService;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real provider failover: the production setup with both Claude and Ollama configured, Claude
 * unreachable. Opt-in like {@link LiveEvalIT} (needs Ollama with the models pulled).
 */
@SpringBootTest
@ActiveProfiles("live-failover")
@Import(TestcontainersConfiguration.class)
@EnabledIfEnvironmentVariable(named = "REPOMIND_EVAL_LIVE", matches = "true")
class ProviderFailoverLiveIT {

	@TempDir
	Path temp;

	@Autowired
	RepoDao repos;

	@Autowired
	IngestJobDao jobs;

	@Autowired
	IngestMessaging.Publisher publisher;

	@Autowired
	AnswerService answers;

	@Autowired
	ChatGateway gateway;

	@Autowired
	MeterRegistry meters;

	@Test
	void answersFromOllamaWhileClaudeIsDownAndStopsTryingClaude() throws Exception {
		assertThat(gateway.providers()).extracting(ChatGateway.Provider::name).containsExactly("anthropic", "ollama");
		Repo repo = EvalFixture.ingest(temp.resolve("library"), EvalFixture.files(), repos, jobs, publisher,
				Duration.ofMinutes(10));

		String[] questions = { "How are late fees calculated?", "How many books can a premium member borrow?",
				"When does a member get suspended?", "How are failed emails retried?", "Which time zone is used?",
				"How does the catalogue search tolerate typos?" };
		for (String question : questions) {
			Answer answer = answers.ask(repo, question);
			assertThat(answer.model()).as(question).containsIgnoringCase("qwen");
			assertThat(answer.answer()).isNotBlank();
		}

		CircuitBreaker claude = gateway.providers().getFirst().breaker();
		assertThat(claude.getState()).isEqualTo(CircuitBreaker.State.OPEN);
		double failed = meters.counter("repomind.chat.calls", "provider", "anthropic", "outcome", "failure").count();
		double skipped = meters.counter("repomind.chat.calls", "provider", "anthropic", "outcome", "skipped").count();
		assertThat(failed).as("calls that hit the dead provider before the circuit opened").isEqualTo(4);
		assertThat(skipped).as("calls that skipped it entirely").isEqualTo(questions.length - 4);
		assertThat(meters.counter("repomind.chat.calls", "provider", "ollama", "outcome", "fallback").count())
			.isEqualTo(questions.length);
	}

}
