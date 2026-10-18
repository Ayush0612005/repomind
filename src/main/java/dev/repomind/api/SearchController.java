package dev.repomind.api;

import java.util.List;
import java.util.UUID;

import dev.repomind.repo.RepoDao;
import dev.repomind.search.HybridSearch;
import dev.repomind.search.SearchHit;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos/{id}/search")
public class SearchController {

	public record SearchResponse(String query, HybridSearch.Mode mode, List<Hit> hits) {
	}

	public record Hit(String citation, String symbol, String kind, double score, Integer vectorRank,
			Integer keywordRank, String content) {

		static Hit of(SearchHit hit) {
			return new Hit(hit.citation(), hit.symbol(), hit.kind(), hit.score(),
					hit.vectorRank() == 0 ? null : hit.vectorRank(), hit.keywordRank() == 0 ? null : hit.keywordRank(),
					hit.content());
		}

	}

	private final HybridSearch search;

	private final RepoDao repos;

	public SearchController(HybridSearch search, RepoDao repos) {
		this.search = search;
		this.repos = repos;
	}

	/** Raw retrieval results: useful on its own, and for inspecting what the Q&A step will see. */
	@GetMapping
	SearchResponse search(@PathVariable UUID id, @RequestParam @NotBlank @Size(max = 1_000) String q,
			@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
			@RequestParam(defaultValue = "HYBRID") HybridSearch.Mode mode) {
		var repo = repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
		return new SearchResponse(q, mode, search.search(repo, q, limit, mode).stream().map(Hit::of).toList());
	}

}
