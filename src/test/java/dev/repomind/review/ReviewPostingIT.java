package dev.repomind.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import dev.repomind.repo.Repo;
import dev.repomind.review.PullRequest.ChangedFile;
import dev.repomind.review.ReviewJob.Status;
import dev.repomind.support.FakePullRequests;
import dev.repomind.support.IntegrationTest;
import dev.repomind.support.ScriptedChatModel;
import dev.repomind.support.TestIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Webhook / API trigger → Kafka → review → publish, against the fake GitHub. */
@IntegrationTest
class ReviewPostingIT {

	private static final String SECRET = "test-webhook-secret";

	private static final String MODEL_REVIEW = """
			{"summary": "Page zero is now accepted.",
			 "comments": [{"path": "VetController.java", "line": 5, "severity": "BLOCKER",
			               "title": "Page zero accepted", "body": "Pages are 1-based."}]}
			""";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	TestIndexer indexer;

	@Autowired
	ScriptedChatModel model;

	@Autowired
	FakePullRequests github;

	@Autowired
	ReviewJobDao jobs;

	private Repo repo;

	@BeforeEach
	void setUp() {
		model.reset();
		model.replyWith(prompt -> MODEL_REVIEW);
		repo = indexer.index("petclinic", TestIndexer.PETCLINIC);
		openPullRequest(7, "sha-1");
	}

	@Test
	void webhookQueuesAReviewThatIsPostedOnceForEachCommit() {
		MvcTestResult first = webhook("pull_request", event("opened", 7, "sha-1", false));
		assertThat(first).hasStatus(HttpStatus.ACCEPTED).bodyJson().extractingPath("$.status").isEqualTo("queued");

		ReviewJob job = awaitTerminal(jobs.find(repo.id(), 7, "sha-1").orElseThrow().id());
		assertThat(job.status()).isEqualTo(Status.POSTED);
		assertThat(job.trigger()).isEqualTo(ReviewJob.Trigger.WEBHOOK);
		assertThat(job.commentsPosted()).isEqualTo(1);
		assertThat(job.githubReviewUrl()).contains("/pull/7#pullrequestreview-");

		List<FakePullRequests.Published> posted = github.publishedTo(repo);
		assertThat(posted).singleElement().satisfies(p -> {
			assertThat(p.review().headSha()).isEqualTo("sha-1");
			assertThat(p.review().comments()).singleElement()
				.extracting(Review.Comment::line)
				.isEqualTo(5);
		});

		// GitHub redelivers the same event: acknowledged, but nothing new is reviewed or posted
		assertThat(webhook("pull_request", event("opened", 7, "sha-1", false))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("duplicate");
		assertThat(github.publishedTo(repo)).hasSize(1);

		// a new push is a new commit and gets its own review
		openPullRequest(7, "sha-2");
		assertThat(webhook("pull_request", event("synchronize", 7, "sha-2", false)))
			.hasStatus(HttpStatus.ACCEPTED);
		assertThat(awaitTerminal(jobs.find(repo.id(), 7, "sha-2").orElseThrow().id()).status())
			.isEqualTo(Status.POSTED);
		assertThat(github.publishedTo(repo)).hasSize(2);
	}

	@Test
	void doesNotPostAReviewForACommitThePullRequestHasMovedPast() {
		openPullRequest(7, "sha-newer"); // GitHub already shows a newer head than the event carried

		webhook("pull_request", event("synchronize", 7, "sha-older", false));

		ReviewJob job = awaitTerminal(jobs.find(repo.id(), 7, "sha-older").orElseThrow().id());
		assertThat(job.status()).isEqualTo(Status.SUPERSEDED);
		assertThat(job.error()).contains("sha-newer");
		assertThat(github.publishedTo(repo)).isEmpty();
	}

	@Test
	void apiTriggerReviewsTheCurrentHeadAndRetriesAFailedPost() {
		github.failNextPublish("GitHub returned 502");

		MvcTestResult queued = mvc.post().uri("/api/repos/{id}/pulls/7/reviews", repo.id()).exchange();
		assertThat(queued).hasStatus(HttpStatus.ACCEPTED).headers().containsHeader("Location");
		UUID jobId = UUID.fromString(JsonPath.read(
				new String(queued.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id"));

		ReviewJob failed = awaitTerminal(jobId);
		assertThat(failed.status()).isEqualTo(Status.FAILED);
		assertThat(failed.error()).contains("502");
		assertThat(mvc.get().uri("/api/review-jobs/{id}", jobId)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("FAILED");

		// asking again retries the same commit's job
		assertThat(mvc.post().uri("/api/repos/{id}/pulls/7/reviews", repo.id())).hasStatus(HttpStatus.ACCEPTED);
		assertThat(awaitTerminal(jobId).status()).isEqualTo(Status.POSTED);

		// and once posted, asking again returns the existing job without posting twice
		assertThat(mvc.post().uri("/api/repos/{id}/pulls/7/reviews", repo.id())).hasStatusOk();
		assertThat(github.publishedTo(repo)).hasSize(1);
		assertThat(mvc.get().uri("/api/review-jobs/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void rejectsUnsignedOrForgedDeliveries() {
		byte[] body = event("opened", 7, "sha-1", false);

		assertThat(mvc.post()
			.uri("/api/github/webhook")
			.header("X-GitHub-Event", "pull_request")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(mvc.post()
			.uri("/api/github/webhook")
			.header("X-GitHub-Event", "pull_request")
			.header("X-Hub-Signature-256", WebhookSignature.sign("wrong-secret", body))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(webhook("pull_request", "not json".getBytes(StandardCharsets.UTF_8)))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(jobs.find(repo.id(), 7, "sha-1")).isEmpty();
	}

	@Test
	void acknowledgesButIgnoresEventsThatNeedNoReview() {
		assertThat(webhook("ping", "{\"zen\": \"Keep it logically awesome.\"}".getBytes(StandardCharsets.UTF_8)))
			.hasStatusOk()
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("pong");
		assertIgnored(webhook("pull_request", event("opened", 7, "sha-1", true)), "draft");
		assertIgnored(webhook("pull_request", event("closed", 7, "sha-1", false)), "closed");
		assertIgnored(webhook("issues", event("opened", 7, "sha-1", false)), "issues");
		assertIgnored(webhook("pull_request", eventFor("acme", "unknown-" + UUID.randomUUID(), "opened", 7, "sha-1")),
				"not tracked");
		assertThat(jobs.find(repo.id(), 7, "sha-1")).isEmpty();
	}

	private void assertIgnored(MvcTestResult result, String reason) {
		assertThat(result).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("ignored");
		assertThat(result).bodyJson().extractingPath("$.reason").asString().contains(reason);
	}

	private void openPullRequest(int number, String headSha) {
		github.put(repo, new PullRequest(number, "Allow page zero", "", headSha, "main", "u",
				List.of(new ChangedFile("VetController.java", "modified", 3, 1, PatchLinesTest.VET_PATCH))));
	}

	private MvcTestResult webhook(String event, byte[] body) {
		return mvc.post()
			.uri("/api/github/webhook")
			.header("X-GitHub-Event", event)
			.header("X-GitHub-Delivery", UUID.randomUUID().toString())
			.header("X-Hub-Signature-256", WebhookSignature.sign(SECRET, body))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private byte[] event(String action, int number, String sha, boolean draft) {
		return eventFor(repo.owner(), repo.name(), action, number, sha, draft);
	}

	private static byte[] eventFor(String owner, String name, String action, int number, String sha) {
		return eventFor(owner, name, action, number, sha, false);
	}

	private static byte[] eventFor(String owner, String name, String action, int number, String sha, boolean draft) {
		return """
				{"action": "%s", "number": %d,
				 "pull_request": {"number": %d, "draft": %s, "state": "open", "head": {"sha": "%s", "ref": "fix"}},
				 "repository": {"name": "%s", "full_name": "%s/%s", "owner": {"login": "%s"}},
				 "sender": {"login": "octocat"}}
				""".formatted(action, number, number, draft, sha, name, owner, name, owner)
			.getBytes(StandardCharsets.UTF_8);
	}

	private ReviewJob awaitTerminal(UUID jobId) {
		return await().atMost(Duration.ofSeconds(30))
			.pollInterval(Duration.ofMillis(100))
			.until(() -> jobs.findById(jobId).orElseThrow(), j -> j.status().isTerminal());
	}

}
