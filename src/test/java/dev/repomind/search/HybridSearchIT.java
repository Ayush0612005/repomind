package dev.repomind.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import dev.repomind.index.ChunkStore;
import dev.repomind.index.EmbeddingService;
import dev.repomind.ingest.IngestJob;
import dev.repomind.ingest.IngestJobDao;
import dev.repomind.ingest.JobStatus;
import dev.repomind.parse.JavaChunker;
import dev.repomind.repo.Repo;
import dev.repomind.repo.RepoDao;
import dev.repomind.search.HybridSearch.Mode;
import dev.repomind.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@IntegrationTest
class HybridSearchIT {

	private static final String OWNER_REPOSITORY = """
			package org.acme.owner;

			interface OwnerRepository {
				Owner findById(int id);
				List<Owner> findByLastName(String lastName);
			}
			""";

	private static final String PET_VALIDATOR = """
			package org.acme.pet;

			class PetValidator {
				void validate(Pet pet) {
					if (pet.getBirthDate() == null) {
						throw new IllegalArgumentException("birth date is required");
					}
				}
			}
			""";

	private static final String VET_CONTROLLER = """
			package org.acme.vet;

			class VetController {
				String showVetList(int page) {
					if (page < 1) {
						throw new IllegalArgumentException("page must be positive");
					}
					return "vets/vetList";
				}
			}
			""";

	@Autowired
	HybridSearch search;

	@Autowired
	ChunkStore store;

	@Autowired
	EmbeddingService embeddings;

	@Autowired
	RepoDao repos;

	@Autowired
	IngestJobDao jobs;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MockMvcTester mvc;

	private final JavaChunker chunker = new JavaChunker();

	private Repo repo;

	@BeforeEach
	void indexRepository() {
		repo = indexed("petclinic", "OwnerRepository.java", OWNER_REPOSITORY, "PetValidator.java", PET_VALIDATOR,
				"VetController.java", VET_CONTROLLER);
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
		Repo other = indexed("other", "OwnerRepository.java", OWNER_REPOSITORY);

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

	/** Chunks, embeds and activates the given {@code path, source} pairs as one generation. */
	private Repo indexed(String prefix, String... files) {
		String name = prefix + "-" + UUID.randomUUID();
		Repo created = repos.upsert("acme", name, "https://github.com/acme/" + name + ".git");
		IngestJob job = jobs.create(created.id());
		for (int i = 0; i < files.length; i += 2) {
			store.insert(created.id(), job.id(), chunker.chunk(files[i], files[i + 1]).chunks());
		}
		List<ChunkStore.PendingChunk> pending;
		while (!(pending = store.findUnembedded(job.id(), 32)).isEmpty()) {
			store.saveEmbeddings(pending.stream().map(ChunkStore.PendingChunk::id).toList(),
					embeddings.embedDocuments(pending.stream().map(ChunkStore.PendingChunk::text).toList()));
		}
		store.activate(created.id(), job.id());
		jobs.transition(job.id(), JobStatus.COMPLETED);
		return repos.findById(created.id()).orElseThrow();
	}

}
