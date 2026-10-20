package dev.repomind.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.repomind.repo.Repo;
import dev.repomind.support.IntegrationTest;
import dev.repomind.support.ScriptedChatModel;
import dev.repomind.support.TestIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@IntegrationTest
class AskJobsIT {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	TestIndexer indexer;

	@Autowired
	ScriptedChatModel model;

	private final CountDownLatch release = new CountDownLatch(1);

	private Repo repo;

	@BeforeEach
	void setUp() {
		model.reset();
		repo = indexer.index("petclinic", TestIndexer.PETCLINIC);
	}

	@AfterEach
	void tearDown() {
		release.countDown();
		model.reset();
	}

	@Test
	void aQuickAnswerIsReturnedDirectly() {
		assertThat(submit("How are vets listed, quickly?", null)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.answer")
			.isEqualTo(ScriptedChatModel.DEFAULT_REPLY);
	}

	@Test
	void aSlowAnswerIsAcceptedAndFetchedLater() {
		slowModel();

		MvcTestResult accepted = submit("How does the slow vet listing work?", null).exchange();
		assertThat(accepted).hasStatus(HttpStatus.ACCEPTED).bodyJson().extractingPath("$.status").isEqualTo("RUNNING");
		String location = accepted.getResponse().getHeader("Location");
		assertThat(location).startsWith("/api/ask-jobs/");

		assertThat(mvc.get().uri(location)).hasStatus(HttpStatus.ACCEPTED);
		release.countDown();
		assertThat(mvc.get().uri(location)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.answer")
			.isEqualTo(ScriptedChatModel.DEFAULT_REPLY);
	}

	@Test
	void aFailedAnswerGetsTheSameErrorAsASynchronousAsk() {
		model.replyWith(prompt -> {
			throw new IllegalStateException("connection refused");
		});

		assertThat(submit("Why does pet validation fail?", null)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("connection refused");
	}

	@Test
	void aJobIsVisibleOnlyToTheClientThatSubmittedIt() {
		slowModel();

		String location = submit("Which owner fields are required?", "admin-test-key").exchange()
			.getResponse()
			.getHeader("Location");

		assertThat(mvc.get().uri(location)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(mvc.get().uri(location).header("X-Api-Key", "admin-test-key")).hasStatus(HttpStatus.ACCEPTED);
		assertThat(mvc.get().uri("/api/ask-jobs/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void validatesTheQuestion() {
		assertThat(submit(" ", null)).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(model.prompts()).isEmpty();
	}

	private void slowModel() {
		model.replyWith(prompt -> {
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return ScriptedChatModel.DEFAULT_REPLY;
		});
	}

	private MockMvcTester.MockMvcRequestBuilder submit(String question, String apiKey) {
		var request = mvc.post()
			.uri("/api/repos/{id}/ask-jobs", repo.id())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"question\": \"" + question + "\"}");
		return apiKey == null ? request : request.header("X-Api-Key", apiKey);
	}

}
