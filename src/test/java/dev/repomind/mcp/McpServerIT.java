package dev.repomind.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.repomind.TestcontainersConfiguration;
import dev.repomind.repo.Repo;
import dev.repomind.support.FakeAiConfiguration;
import dev.repomind.support.ScriptedChatModel;
import dev.repomind.support.TestIndexer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RepoMind as an MCP server, exercised by the official MCP Java SDK client over Streamable HTTP
 * on a real port, the way an agent connects to it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, FakeAiConfiguration.class, TestIndexer.class })
class McpServerIT {

	@LocalServerPort
	int port;

	@Autowired
	TestIndexer indexer;

	@Autowired
	ScriptedChatModel model;

	@Autowired
	JsonMapper json;

	private Repo repo;

	private McpSyncClient client;

	@BeforeEach
	void setUp() {
		model.reset();
		repo = indexer.index("petclinic", TestIndexer.PETCLINIC);
		client = connect(null);
	}

	@AfterEach
	void tearDown() {
		client.closeGracefully();
	}

	@Test
	@SuppressWarnings("unchecked")
	void advertisesFourToolsWithTheirParameters() {
		List<Tool> tools = client.listTools().tools();

		assertThat(tools).extracting(Tool::name)
			.containsExactlyInAnyOrder("list_repositories", "search_code", "explain_class", "ask_codebase");
		Tool search = tools.stream().filter(t -> t.name().equals("search_code")).findFirst().orElseThrow();
		assertThat(search.description()).contains("hybrid");
		Map<String, Object> schema = search.inputSchema();
		assertThat((Map<String, Object>) schema.get("properties")).containsKeys("repository", "query", "limit");
		assertThat((List<String>) schema.get("required")).containsExactlyInAnyOrder("repository", "query");
	}

	@Test
	void listsAndSearchesIndexedRepositories() {
		JsonNode repos = call("list_repositories", Map.of());
		assertThat(repos.findValuesAsString("repository")).contains(repo.fullName());

		JsonNode hits = call("search_code", Map.of("repository", repo.fullName(), "query", "showVetList page", "limit", 3));
		assertThat(hits.size()).isBetween(1, 3);
		assertThat(hits.get(0).get("location").asString()).startsWith("VetController.java:");
		assertThat(hits.get(0).get("code").asString()).contains("showVetList");
	}

	@Test
	void explainsAClassWithItsMembersAndPointsToSearchWhenTheNameIsWrong() {
		JsonNode outline = call("explain_class", Map.of("repository", repo.id().toString(), "className", "VetController"))
			.get(0);
		assertThat(outline.get("symbol").asString()).isEqualTo("org.acme.vet.VetController");
		assertThat(outline.get("skeleton").asString()).contains("class VetController");
		assertThat(outline.get("members").findValuesAsString("symbol")).anyMatch(s -> s.startsWith("VetController#showVetList("));

		CallToolResult missing = client.callTool(new CallToolRequest("explain_class",
				Map.of("repository", repo.fullName(), "className", "NoSuchType")));
		assertThat(missing.isError()).isTrue();
		assertThat(text(missing)).contains("No type named 'NoSuchType'", "search_code");
	}

	@Test
	void answersQuestionsWithCitations() {
		JsonNode answer = call("ask_codebase", Map.of("repository", repo.fullName(), "question", "How is the vet list paged?"));

		assertThat(answer.get("answer").asString()).isEqualTo(ScriptedChatModel.DEFAULT_REPLY);
		assertThat(answer.get("citations").get(0).get("location").asString()).startsWith("VetController.java:");
		assertThat(model.prompts()).hasSize(1);
	}

	@Test
	void appliesApiKeysAndTokenBudgetsToToolCalls() {
		assertThatThrownBy(() -> connect("not-a-real-key")).hasStackTraceContaining("401");

		McpSyncClient limited = connect("budget-test-key"); // 1 token per day
		try {
			assertThat(limited.callTool(new CallToolRequest("ask_codebase",
					Map.of("repository", repo.fullName(), "question", "How is the vet list paged?")))
				.isError()).isFalse();

			CallToolResult refused = limited.callTool(new CallToolRequest("ask_codebase",
					Map.of("repository", repo.fullName(), "question", "Where are owners looked up?")));
			assertThat(refused.isError()).isTrue();
			assertThat(text(refused)).contains("token budget");

			// data tools spend no model tokens, so they keep working
			assertThat(limited.callTool(new CallToolRequest("list_repositories", Map.of())).isError()).isFalse();
		}
		finally {
			limited.closeGracefully();
		}
	}

	private McpSyncClient connect(String apiKey) {
		HttpRequest.Builder request = HttpRequest.newBuilder();
		if (apiKey != null) {
			request.header("X-Api-Key", apiKey);
		}
		McpSyncClient c = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
				.endpoint("/mcp")
				.requestBuilder(request)
				.build())
			.requestTimeout(Duration.ofSeconds(30))
			.build();
		c.initialize();
		return c;
	}

	private JsonNode call(String tool, Map<String, Object> arguments) {
		CallToolResult result = client.callTool(new CallToolRequest(tool, arguments));
		assertThat(result.isError()).as("%s failed: %s", tool, text(result)).isFalse();
		return json.readTree(text(result));
	}

	private static String text(CallToolResult result) {
		return ((TextContent) result.content().getFirst()).text();
	}

}
