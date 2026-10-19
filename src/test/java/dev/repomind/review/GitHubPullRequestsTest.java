package dev.repomind.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import dev.repomind.ingest.GitHubRepoRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubPullRequestsTest {

	private static final String API = "https://api.github.test";

	private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "shop");

	private static final String PULL = """
			{"number": 7, "title": "Allow page zero", "body": null, "html_url": "https://github.com/acme/shop/pull/7",
			 "state": "open", "head": {"sha": "abc123", "ref": "fix"}, "base": {"sha": "def456", "ref": "main"}}
			""";

	private RestClient.Builder builder = RestClient.builder();

	private MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	@Test
	void fetchesPullRequestAndPagesThroughChangedFiles() {
		GitHubPullRequests client = new GitHubPullRequests(builder, new GitHubProperties(API, "s3cret"));
		server.expect(requestTo(API + "/repos/acme/shop/pulls/7"))
			.andExpect(header("Authorization", "Bearer s3cret"))
			.andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
			.andRespond(withSuccess(PULL, MediaType.APPLICATION_JSON));
		server.expect(requestTo(API + "/repos/acme/shop/pulls/7/files?per_page=100&page=1"))
			.andRespond(withSuccess(files(0, GitHubPullRequests.PAGE_SIZE), MediaType.APPLICATION_JSON));
		server.expect(requestTo(API + "/repos/acme/shop/pulls/7/files?per_page=100&page=2"))
			.andRespond(withSuccess("""
					[{"filename": "logo.png", "status": "added", "additions": 0, "deletions": 0}]
					""", MediaType.APPLICATION_JSON));

		PullRequest pr = client.fetch(REPO, 7);

		server.verify();
		assertThat(pr.title()).isEqualTo("Allow page zero");
		assertThat(pr.body()).isEmpty();
		assertThat(pr.headSha()).isEqualTo("abc123");
		assertThat(pr.baseRef()).isEqualTo("main");
		assertThat(pr.files()).hasSize(101);
		assertThat(pr.files().getFirst().patch()).isEqualTo("@@ -1 +1 @@\n-a\n+b");
		assertThat(pr.files().getLast().patch()).isNull();
	}

	@Test
	void sendsNoAuthorizationWithoutAToken() {
		GitHubPullRequests client = new GitHubPullRequests(builder, new GitHubProperties(API, ""));
		server.expect(requestTo(API + "/repos/acme/shop/pulls/7"))
			.andExpect(headerDoesNotExist("Authorization"))
			.andRespond(withSuccess(PULL, MediaType.APPLICATION_JSON));
		server.expect(requestTo(API + "/repos/acme/shop/pulls/7/files?per_page=100&page=1"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		assertThat(client.fetch(REPO, 7).files()).isEmpty();
	}

	@Test
	void mapsErrorStatuses() {
		GitHubPullRequests client = new GitHubPullRequests(builder, new GitHubProperties(API, null));
		server.expect(requestTo(API + "/repos/acme/shop/pulls/404")).andRespond(withStatus(HttpStatus.NOT_FOUND));
		server.expect(requestTo(API + "/repos/acme/shop/pulls/403")).andRespond(withStatus(HttpStatus.FORBIDDEN));

		assertThatThrownBy(() -> client.fetch(REPO, 404)).isInstanceOf(PullRequestNotFoundException.class)
			.hasMessageContaining("/repos/acme/shop/pulls/404");
		assertThatThrownBy(() -> client.fetch(REPO, 403)).isInstanceOf(GitHubUnavailableException.class)
			.hasMessageContaining("403");
	}

	private static String files(int from, int count) {
		return IntStream.range(from, from + count)
			.mapToObj(i -> """
					{"filename": "src/F%d.java", "status": "modified", "additions": 1, "deletions": 1,
					 "patch": "@@ -1 +1 @@\\n-a\\n+b", "sha": "x"}""".formatted(i))
			.collect(Collectors.joining(",", "[", "]"));
	}

}
