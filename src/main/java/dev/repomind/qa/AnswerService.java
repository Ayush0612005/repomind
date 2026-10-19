package dev.repomind.qa;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import dev.repomind.index.EmbeddingService;
import dev.repomind.limits.ModelBudget;
import dev.repomind.limits.TokenUsage;
import dev.repomind.qa.cache.AnswerCache;
import dev.repomind.repo.Repo;
import dev.repomind.search.HybridSearch;
import dev.repomind.search.NotIndexedException;
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

/**
 * Retrieval-augmented answers: answer cache → hybrid search → grounded prompt → chat model → cited
 * answer, which is then cached.
 */
@Service
public class AnswerService {

	private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

	static final String NO_SOURCES = "I couldn't find any code in this repository related to that question.";

	private final HybridSearch search;

	private final EmbeddingService embeddings;

	private final AnswerCache cache;

	private final ChatModel chat;

	private final QaProperties properties;

	private final MeterRegistry meters;

	public AnswerService(HybridSearch search, EmbeddingService embeddings, AnswerCache cache, ChatModel chat,
			QaProperties properties, MeterRegistry meters) {
		this.search = search;
		this.embeddings = embeddings;
		this.cache = cache;
		this.chat = chat;
		this.properties = properties;
		this.meters = meters;
	}

	public Answer ask(Repo repo, String question) {
		return ask(repo, question, ModelBudget.UNLIMITED);
	}

	/**
	 * @param budget reserved only when the model is actually called, so cached answers are served
	 * even to clients whose budget is used up
	 */
	public Answer ask(Repo repo, String question, ModelBudget budget) {
		if (repo.activeJobId() == null) {
			throw new NotIndexedException(repo);
		}
		if (question == null || question.isBlank()) {
			throw new IllegalArgumentException("Question must not be blank");
		}
		long start = System.nanoTime();

		// cheapest first: an exact repeat needs neither an embedding nor a model call
		Optional<Answer> cached = cache.exact(repo, question);
		if (cached.isPresent()) {
			return served(cached.get(), start);
		}
		float[] queryVector = embeddings.embedQuery(question);
		cached = cache.semantic(repo, question, queryVector);
		if (cached.isPresent()) {
			return served(cached.get(), start);
		}
		meters.counter("repomind.qa.cache", "result", "miss").increment();

		Answer answer = generate(repo, question, queryVector, budget, start);
		cache.store(repo, question, queryVector, answer);
		return answer;
	}

	private Answer served(Answer cached, long start) {
		meters.counter("repomind.qa.cache", "result", cached.cache().status().name().toLowerCase(Locale.ROOT))
			.increment();
		return cached.withCache(cached.cache(), elapsedMs(start));
	}

	private Answer generate(Repo repo, String question, float[] queryVector, ModelBudget budget, long start) {
		List<SearchHit> hits = search.search(repo, question, queryVector, properties.retrieveLimit(),
				HybridSearch.Mode.HYBRID);
		if (hits.isEmpty()) {
			// nothing to ground an answer in: skip the model call rather than invite a guess
			return new Answer(NO_SOURCES, List.of(), List.of(), null, elapsedMs(start), Answer.Cache.MISS);
		}
		GroundedPrompt prompt = GroundedPrompt.build(repo.fullName(), question, hits, properties.maxContextTokens());
		budget.reserve();

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
		// the provider bills the call whether or not the answer is usable
		budget.charge(TokenUsage.of(response, prompt.system() + prompt.user(), text));
		if (text == null || text.isBlank()) {
			throw new ModelUnavailableException("The language model returned an empty answer", null);
		}
		String model = response.getMetadata() == null ? null : response.getMetadata().getModel();
		return new Answer(text.strip(), prompt.citedIn(text), prompt.sources(), blankToNull(model), elapsedMs(start),
				Answer.Cache.MISS);
	}

	private static long elapsedMs(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

}
