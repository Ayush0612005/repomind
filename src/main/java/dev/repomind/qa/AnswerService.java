package dev.repomind.qa;

import java.util.List;

import dev.repomind.repo.Repo;
import dev.repomind.search.HybridSearch;
import dev.repomind.search.SearchHit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/** Retrieval-augmented answers: hybrid search → grounded prompt → chat model → cited answer. */
@Service
public class AnswerService {

	private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

	static final String NO_SOURCES = "I couldn't find any code in this repository related to that question.";

	private final HybridSearch search;

	private final ChatModel chat;

	private final QaProperties properties;

	private final MeterRegistry meters;

	public AnswerService(HybridSearch search, ChatModel chat, QaProperties properties, MeterRegistry meters) {
		this.search = search;
		this.chat = chat;
		this.properties = properties;
		this.meters = meters;
	}

	public Answer ask(Repo repo, String question) {
		long start = System.nanoTime();
		List<SearchHit> hits = search.search(repo, question, properties.retrieveLimit(), HybridSearch.Mode.HYBRID);
		if (hits.isEmpty()) {
			// nothing to ground an answer in: skip the model call rather than invite a guess
			return new Answer(NO_SOURCES, List.of(), List.of(), null, elapsedMs(start));
		}
		GroundedPrompt prompt = GroundedPrompt.build(repo.fullName(), question, hits, properties.maxContextTokens());

		ChatResponse response;
		Timer.Sample sample = Timer.start(meters);
		try {
			response = chat.call(new Prompt(List.of(new SystemMessage(prompt.system()), new UserMessage(prompt.user()))));
		}
		catch (RuntimeException ex) {
			meters.counter("repomind.qa.failures").increment();
			log.warn("Chat model call failed for {}: {}", repo.fullName(), ex.getMessage());
			throw new ModelUnavailableException("The language model is unavailable: " + ex.getMessage(), ex);
		}
		finally {
			sample.stop(meters.timer("repomind.qa.model.latency"));
		}

		String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
		if (text == null || text.isBlank()) {
			throw new ModelUnavailableException("The language model returned an empty answer", null);
		}
		String model = response.getMetadata() == null ? null : response.getMetadata().getModel();
		return new Answer(text.strip(), prompt.citedIn(text), prompt.sources(), blankToNull(model), elapsedMs(start));
	}

	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

}
