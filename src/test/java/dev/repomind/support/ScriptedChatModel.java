package dev.repomind.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Chat model stand-in for tests: records every prompt and replies via a swappable script, so tests
 * can assert on exactly what would have been sent to Claude without network access or API cost.
 */
public class ScriptedChatModel implements ChatModel {

	public static final String DEFAULT_REPLY = "The vet list is rendered by showVetList [1], which validates the page [1][2].";

	private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

	private volatile Function<Prompt, String> script = prompt -> DEFAULT_REPLY;

	@Override
	public ChatResponse call(Prompt prompt) {
		prompts.add(prompt);
		return new ChatResponse(List.of(new Generation(new AssistantMessage(script.apply(prompt)))));
	}

	public void replyWith(Function<Prompt, String> script) {
		this.script = script;
	}

	public List<Prompt> prompts() {
		return prompts;
	}

	public void reset() {
		prompts.clear();
		script = prompt -> DEFAULT_REPLY;
	}

}
