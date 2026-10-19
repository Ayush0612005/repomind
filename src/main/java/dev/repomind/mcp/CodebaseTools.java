package dev.repomind.mcp;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.repomind.limits.ApiClient;
import dev.repomind.limits.LimitsProperties;
import dev.repomind.limits.ModelBudget;
import dev.repomind.limits.TokenBudget;
import dev.repomind.qa.Answer;
import dev.repomind.qa.AnswerService;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.search.CodeNavigator;
import dev.repomind.search.HybridSearch;
import dev.repomind.search.SearchHit;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * RepoMind's MCP tools. Three return data rather than prose, because the calling agent has its own
 * model to reason with and needs exact code and locations; {@code ask_codebase} is the one tool
 * that spends RepoMind's model budget. Errors are thrown as exceptions, which MCP reports back to
 * the agent as tool errors with the message, so messages say what to do next.
 */
@Component
public class CodebaseTools {

	public record RepositorySummary(String repository, String id, String status, String branch, String commit) {
	}

	public record CodeHit(String location, String symbol, String kind, String code) {
	}

	private static final int MAX_SEARCH_RESULTS = 20;

	private final RepoDao repos;

	private final HybridSearch search;

	private final CodeNavigator navigator;

	private final AnswerService answers;

	private final TokenBudget budgets;

	private final LimitsProperties limits;

	public CodebaseTools(RepoDao repos, HybridSearch search, CodeNavigator navigator, AnswerService answers,
			TokenBudget budgets, LimitsProperties limits) {
		this.repos = repos;
		this.search = search;
		this.navigator = navigator;
		this.answers = answers;
		this.budgets = budgets;
		this.limits = limits;
	}

	@McpTool(name = "list_repositories",
			description = "Lists the GitHub repositories RepoMind has indexed, with their status and indexed commit. "
					+ "Use the 'repository' value (owner/name) in the other tools.")
	public List<RepositorySummary> listRepositories() {
		return repos.findAll()
			.stream()
			.map(r -> new RepositorySummary(r.fullName(), r.id().toString(), r.status().name(), r.defaultBranch(),
					r.commitSha()))
			.toList();
	}

	@McpTool(name = "search_code",
			description = "Searches a repository's code with hybrid semantic + keyword search. Returns the most "
					+ "relevant methods and type outlines with file:line locations and their source. Works for "
					+ "natural-language questions ('where are late fees calculated') and identifiers ('FineCalculator').")
	public List<CodeHit> searchCode(
			@McpToolParam(description = "Repository as owner/name, or its id from list_repositories") String repository,
			@McpToolParam(description = "What to look for") String query,
			@McpToolParam(description = "Maximum results, 1-20 (default 8)", required = false) Integer limit) {
		int max = limit == null ? 8 : Math.clamp(limit, 1, MAX_SEARCH_RESULTS);
		return search.search(repository(repository), query, max, HybridSearch.Mode.HYBRID)
			.stream()
			.map(CodebaseTools::hit)
			.toList();
	}

	@McpTool(name = "explain_class",
			description = "Returns a class's outline: its signature-only skeleton, every method and constructor with "
					+ "file:line locations, and other code that refers to it. Accepts a simple name (LoanService), "
					+ "a nested name (Outer.Inner) or a fully qualified name.")
	public List<CodeNavigator.ClassOutline> explainClass(
			@McpToolParam(description = "Repository as owner/name, or its id from list_repositories") String repository,
			@McpToolParam(description = "Class, interface, enum or record name") String className) {
		Repo repo = repository(repository);
		List<CodeNavigator.ClassOutline> outlines = navigator.outline(repo, className);
		if (outlines.isEmpty()) {
			throw new IllegalArgumentException("No type named '" + className + "' in " + repo.fullName()
					+ ". Use search_code to find the right name.");
		}
		return outlines;
	}

	@McpTool(name = "ask_codebase",
			description = "Answers a question about a repository using its code, citing file:line for each claim. "
					+ "Uses RepoMind's language model and counts against the caller's token budget; prefer "
					+ "search_code or explain_class when you can reason over the code yourself.")
	public Answer askCodebase(
			@McpToolParam(description = "Repository as owner/name, or its id from list_repositories") String repository,
			@McpToolParam(description = "The question") String question) {
		return answers.ask(repository(repository), question, budget());
	}

	private Repo repository(String reference) {
		if (reference == null || reference.isBlank()) {
			throw new IllegalArgumentException("repository is required (owner/name); call list_repositories");
		}
		String ref = reference.strip();
		Repo repo;
		try {
			repo = repos.findById(UUID.fromString(ref)).orElse(null);
		}
		catch (IllegalArgumentException notAUuid) {
			String[] parts = ref.replaceFirst("(?i)^https?://github\\.com/", "").split("/");
			repo = parts.length == 2 ? repos.findByFullName(parts[0], parts[1]).orElse(null) : null;
		}
		if (repo == null) {
			throw new IllegalArgumentException("Repository '" + ref + "' is not tracked by RepoMind; call "
					+ "list_repositories to see what is indexed");
		}
		return repo;
	}

	/**
	 * The budget of the client the limits interceptor identified for this MCP request. Tool calls run
	 * on the request thread, so the request attribute is visible here; if it is ever missing while
	 * limits are on, refuse rather than spend tokens unaccounted.
	 */
	private ModelBudget budget() {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		Object client = request == null ? null
				: request.getAttribute(ApiClient.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (client instanceof ApiClient apiClient) {
			return budgets.forClient(apiClient);
		}
		if (limits.enabled()) {
			throw new IllegalStateException("Could not identify the API client for this request");
		}
		return ModelBudget.UNLIMITED;
	}

	private static CodeHit hit(SearchHit h) {
		return new CodeHit(h.citation(), h.symbol(), h.kind().toLowerCase(Locale.ROOT), h.content());
	}

}
