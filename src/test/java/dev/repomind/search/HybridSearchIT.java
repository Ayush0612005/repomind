package dev.repomind.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.search.HybridSearch.Mode;
import dev.repomind.support.IntegrationTest;
import dev.repomind.support.TestIndexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class HybridSearchIT {

	@Autowired
	HybridSearch search;

	@Autowired
	TestIndexer indexer;

	@Autowired
	RepoDao repos;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvcTester mvc;

	private Repo repo;

	@BeforeEach
	void indexRepository() {
		repo = indexer.index("petclinic", TestIndexer.PETCLINIC);
	}

	@Test
	void keywordSearchFindsExactIdentifiers() {
		List<SearchHit> hits = search.search(repo, "findByLastName", 5, Mode.KEYWORD);

		assertThat(hits).isNotEmpty();
		assertThat(hits.getFirst().filePath()).isEqualTo("OwnerRepository.java");
		assertThat(hits).allSatisfy(h -> assertThat(h.vectorRank()).isZero());
	}

	@Test
	void vectorSearchFindsCodeByMeaning() {
		List<SearchHit> hits = search.search(repo, "how is the pet birth date validated", 5, Mode.VECTOR);

		assertThat(hits.getFirst().filePath()).isEqualTo("PetValidator.java");
		assertThat(hits).allSatisfy(h -> assertThat(h.keywordRank()).isZero());
	}

	@Test
	void hybridSearchFusesBothRankingsAndCitesLines() {
		List<SearchHit> hits = search.search(repo, "showVetList page", 3, Mode.HYBRID);

		SearchHit top = hits.getFirst();
		assertThat(top.symbol()).contains("showVetList");
		assertThat(top.vectorRank()).isPositive();
		assertThat(top.keywordRank()).isPositive();
		assertThat(top.citation()).matches("VetController\\.java:\\d+-\\d+");
		assertThat(hits).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
	}

	@Test
	void onlySearchesTheRepositorysActiveGeneration() {
		Repo other = indexer.index("other", TestIndexer.PETCLINIC[0], TestIndexer.PETCLINIC[1]);

		List<Long> ids = search.search(repo, "owner find by last name", 50, Mode.HYBRID)
			.stream()
			.map(SearchHit::chunkId)
			.toList();

		assertThat(ids).isNotEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM code_chunks WHERE id IN (:ids) AND ingest_job_id <> :job")
			.param("ids", ids)
			.param("job", repo.activeJobId())
			.query(Integer.class)
			.single()).isZero();
		assertThat(other.activeJobId()).isNotEqualTo(repo.activeJobId());
	}

	@Test
	void searchEndpointValidatesInputAndRequiresAnIndex() {
		assertThat(mvc.get().uri("/api/repos/{id}/search?q={q}", repo.id(), "vet list")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.hits[0].citation")
			.asString()
			.startsWith("VetController.java:");

		assertThat(mvc.get().uri("/api/repos/{id}/search?q=", repo.id())).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/repos/{id}/search?q=x&limit=0", repo.id())).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/repos/{id}/search?q=x&mode=FUZZY", repo.id()))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/repos/{id}/search?q=x", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);

		Repo unindexed = repos.upsert("acme", "new-" + UUID.randomUUID(), "https://github.com/acme/new.git");
		assertThat(mvc.get().uri("/api/repos/{id}/search?q=x", unindexed.id())).hasStatus(HttpStatus.CONFLICT);
	}

}
