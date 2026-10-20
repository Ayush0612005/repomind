package dev.repomind.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.support.IntegrationTest;
import dev.repomind.support.ScriptedChatModel;
import dev.repomind.support.TestIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class AskIT {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	TestIndexer indexer;

	@Autowired
	RepoDao repos;

	@Autowired
	ScriptedChatModel model;

	private Repo repo;

	@BeforeEach
	void setUp() {
		model.reset();
		repo = indexer.index("petclinic", TestIndexer.PETCLINIC);
	}

	@Test
	void answersFromRetrievedCodeWithCitations() {
		var response = ask(repo.id(), "How is the vet list paged?");

		assertThat(response).hasStatusOk();
		assertThat(response).bodyJson().extractingPath("$.answer").isEqualTo(ScriptedChatModel.DEFAULT_REPLY);
		assertThat(response).bodyJson()
			.extractingPath("$.citations[0].location")
			.asString()
			.startsWith("VetController.java:");
		assertThat(response).bodyJson().extractingPath("$.citations.length()").isEqualTo(2);
		assertThat(response).bodyJson().extractingPath("$.sources[0].index").isEqualTo(1);

		Prompt sent = model.prompts().getLast();
		assertThat(sent.getInstructions()).extracting(m -> m.getMessageType())
			.containsExactly(MessageType.SYSTEM, MessageType.USER);
		assertThat(sent.getInstructions().getFirst().getText()).contains(repo.fullName());
		assertThat(sent.getInstructions().getLast().getText()).contains("[1] VetController.java:", "showVetList")
			.contains("Question: How is the vet list paged?")
			.endsWith("cite each claim with its source number, e.g. [1].");
	}

	@Test
	void ignoresCitationsToSourcesThatWereNotProvided() {
		model.replyWith(prompt -> "It is in [42], or maybe [1].");

		assertThat(ask(repo.id(), "Where are owners looked up?")).bodyJson()
			.extractingPath("$.citations[*].index")
			.asArray()
			.containsExactly(1);
	}

	@Test
	void reportsAnUnavailableModelAs503() {
		model.replyWith(prompt -> {
			throw new IllegalStateException("connection refused");
		});

		assertThat(ask(repo.id(), "How are pets validated?")).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("connection refused");
	}

	@Test
	void validatesTheRequestAndRequiresAnIndex() {
		assertThat(ask(repo.id(), " ")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(ask(repo.id(), "x".repeat(2_001))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(ask(UUID.randomUUID(), "anything")).hasStatus(HttpStatus.NOT_FOUND);

		Repo unindexed = repos.upsert("acme", "new-" + UUID.randomUUID(), "https://github.com/acme/new.git");
		assertThat(ask(unindexed.id(), "anything")).hasStatus(HttpStatus.CONFLICT);
		assertThat(model.prompts()).isEmpty();
	}

	private MockMvcTester.MockMvcRequestBuilder ask(UUID repoId, String question) {
		return mvc.post()
			.uri("/api/repos/{id}/ask", repoId)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"question\": \"" + question + "\"}");
	}

}
