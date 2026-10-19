package dev.repomind.review;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.review.ReviewJob.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns GitHub {@code pull_request} webhook deliveries into review jobs. Anything that should not
 * be reviewed is acknowledged with a reason rather than an error, because GitHub treats errors as
 * failed deliveries and shows them as broken in the webhook settings.
 */
@Service
public class WebhookHandler {

	private static final Logger log = LoggerFactory.getLogger(WebhookHandler.class);

	/** New code to review: opened, new commits pushed, reopened, or taken out of draft. */
	private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

	public record Outcome(String status, String reason, UUID jobId) {

		static Outcome ignored(String reason) {
			return new Outcome("ignored", reason, null);
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PullRequestEvent(String action, @JsonProperty("pull_request") PullPayload pullRequest,
			RepositoryPayload repository) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PullPayload(int number, boolean draft, HeadPayload head) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record HeadPayload(String sha) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RepositoryPayload(String name, OwnerPayload owner) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record OwnerPayload(String login) {
	}

	private final GitHubProperties properties;

	private final JsonMapper json;

	private final RepoDao repos;

	private final ReviewJobService reviewJobs;

	public WebhookHandler(GitHubProperties properties, JsonMapper json, RepoDao repos, ReviewJobService reviewJobs) {
		this.properties = properties;
		this.json = json;
		this.repos = repos;
		this.reviewJobs = reviewJobs;
	}

	public Outcome handle(String event, String signature, byte[] payload) {
		if (!StringUtils.hasText(properties.webhookSecret())) {
			throw new WebhookRejectedException(HttpStatus.SERVICE_UNAVAILABLE,
					"Webhooks are disabled: set repomind.github.webhook-secret");
		}
		// verify before parsing: unauthenticated input never reaches the JSON parser
		if (!WebhookSignature.isValid(properties.webhookSecret(), payload, signature)) {
			throw new WebhookRejectedException(HttpStatus.UNAUTHORIZED, "Invalid X-Hub-Signature-256");
		}
		if ("ping".equals(event)) {
			return new Outcome("pong", null, null);
		}
		if (!"pull_request".equals(event)) {
			return Outcome.ignored("event '" + event + "' is not handled");
		}

		PullRequestEvent pr;
		try {
			pr = json.readValue(payload, PullRequestEvent.class);
		}
		catch (JacksonException ex) {
			throw new WebhookRejectedException(HttpStatus.BAD_REQUEST, "Malformed pull_request payload");
		}
		if (pr.pullRequest() == null || pr.repository() == null || pr.repository().owner() == null
				|| pr.pullRequest().head() == null) {
			throw new WebhookRejectedException(HttpStatus.BAD_REQUEST, "Incomplete pull_request payload");
		}
		if (!REVIEW_ACTIONS.contains(pr.action())) {
			return Outcome.ignored("action '" + pr.action() + "' does not change the code");
		}
		if (pr.pullRequest().draft()) {
			return Outcome.ignored("draft pull request");
		}

		String owner = pr.repository().owner().login();
		String name = pr.repository().name();
		Repo repo = repos.findByFullName(owner, name).orElse(null);
		if (repo == null) {
			return Outcome.ignored(owner + "/" + name + " is not tracked by RepoMind");
		}
		if (repo.activeJobId() == null) {
			return Outcome.ignored(repo.fullName() + " has not been indexed yet");
		}

		ReviewJobService.Submission submission = reviewJobs.submit(repo, pr.pullRequest().number(),
				pr.pullRequest().head().sha(), Trigger.WEBHOOK);
		log.info("Webhook {} for {}#{} → review job {} ({})", pr.action(), repo.fullName(),
				pr.pullRequest().number(), submission.job().id(), submission.created() ? "queued" : "duplicate");
		return new Outcome(submission.created() ? "queued" : "duplicate", null, submission.job().id());
	}

}
