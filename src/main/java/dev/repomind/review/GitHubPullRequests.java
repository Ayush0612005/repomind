package dev.repomind.review;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.repomind.ingest.GitHubRepoRef;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Reads pull requests and publishes reviews through the GitHub REST API. */
@Component
public class GitHubPullRequests implements PullRequestSource, ReviewPublisher {

	static final int PAGE_SIZE = 100;

	/** GitHub itself stops listing files after 3,000; ten pages is plenty for a reviewable PR. */
	private static final int MAX_PAGES = 10;

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PullDto(int number, String title, String body, @JsonProperty("html_url") String htmlUrl, RefDto head,
			RefDto base) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RefDto(String sha, String ref) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record FileDto(String filename, String status, int additions, int deletions, String patch) {
	}

	record CreateReviewDto(@JsonProperty("commit_id") String commitId, String event, String body,
			List<ReviewCommentDto> comments) {
	}

	record ReviewCommentDto(String path, int line, String side, String body) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ReviewDto(long id, @JsonProperty("html_url") String htmlUrl) {
	}

	private static final int MAX_ERROR_BODY = 500;

	private final RestClient http;

	private final boolean authenticated;

	@Autowired
	public GitHubPullRequests(GitHubProperties properties) {
		this(RestClient.builder(), properties);
	}

	GitHubPullRequests(RestClient.Builder builder, GitHubProperties properties) {
		builder.baseUrl(properties.apiUrl())
			.defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
			.defaultHeader("X-GitHub-Api-Version", "2022-11-28")
			.defaultHeader(HttpHeaders.USER_AGENT, "repomind")
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
				String path = request.getURI().getPath();
				if (response.getStatusCode().value() == 404) {
					throw new PullRequestNotFoundException("GitHub has no " + path
							+ " (private repositories need repomind.github.token)");
				}
				// GitHub explains 422s (e.g. a line outside the diff) in the body; keep it for the job's error
				String detail = new String(response.getBody().readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
				throw new GitHubUnavailableException("GitHub returned " + response.getStatusCode().value() + " for "
						+ path + (detail.isBlank() ? "" : ": " + detail.strip()), null);
			});
		this.authenticated = StringUtils.hasText(properties.token());
		if (authenticated) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token());
		}
		this.http = builder.build();
	}

	/**
	 * Creates a submitted review with inline comments. The event is always COMMENT: an automated
	 * reviewer should inform the humans, not approve or block the merge on their behalf.
	 */
	@Override
	public String publish(GitHubRepoRef repo, Review review) {
		if (!authenticated) {
			throw new GitHubUnavailableException("Posting reviews requires repomind.github.token (GITHUB_TOKEN)", null);
		}
		var comments = review.comments()
			.stream()
			.map(c -> new ReviewCommentDto(c.path(), c.line(), "RIGHT", ReviewMarkdown.comment(c)))
			.toList();
		try {
			ReviewDto posted = http.post()
				.uri("/repos/{owner}/{name}/pulls/{number}/reviews", repo.owner(), repo.name(), review.pullRequest())
				.body(new CreateReviewDto(review.headSha(), "COMMENT", ReviewMarkdown.body(review), comments))
				.retrieve()
				.body(ReviewDto.class);
			return posted == null ? null : posted.htmlUrl();
		}
		catch (ResourceAccessException ex) {
			throw new GitHubUnavailableException("GitHub is unreachable: " + ex.getMessage(), ex);
		}
	}

	@Override
	public PullRequest fetch(GitHubRepoRef repo, int number) {
		try {
			PullDto pull = http.get()
				.uri("/repos/{owner}/{name}/pulls/{number}", repo.owner(), repo.name(), number)
				.retrieve()
				.body(PullDto.class);
			if (pull == null) {
				throw new GitHubUnavailableException("GitHub returned an empty pull request", null);
			}
			return new PullRequest(pull.number(), pull.title(), pull.body() == null ? "" : pull.body(),
					pull.head().sha(), pull.base().ref(), pull.htmlUrl(), files(repo, number));
		}
		catch (ResourceAccessException ex) {
			throw new GitHubUnavailableException("GitHub is unreachable: " + ex.getMessage(), ex);
		}
	}

	private List<PullRequest.ChangedFile> files(GitHubRepoRef repo, int number) {
		List<PullRequest.ChangedFile> files = new ArrayList<>();
		for (int page = 1; page <= MAX_PAGES; page++) {
			FileDto[] batch = http.get()
				.uri("/repos/{owner}/{name}/pulls/{number}/files?per_page={size}&page={page}", repo.owner(),
						repo.name(), number, PAGE_SIZE, page)
				.retrieve()
				.body(FileDto[].class);
			if (batch == null) {
				break;
			}
			for (FileDto f : batch) {
				files.add(new PullRequest.ChangedFile(f.filename(), f.status(), f.additions(), f.deletions(),
						f.patch()));
			}
			if (batch.length < PAGE_SIZE) {
				break;
			}
		}
		return files;
	}

}
