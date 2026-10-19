package dev.repomind.review;

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

/** Reads pull requests through the GitHub REST API. */
@Component
public class GitHubPullRequests implements PullRequestSource {

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

	private final RestClient http;

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
				throw new GitHubUnavailableException(
						"GitHub returned " + response.getStatusCode().value() + " for " + path, null);
			});
		if (StringUtils.hasText(properties.token())) {
			builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token());
		}
		this.http = builder.build();
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
