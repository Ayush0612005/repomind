package dev.repomind.support;

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
import org.springframework.stereotype.Component;

/** Indexes in-memory sources directly (no Git or Kafka) for tests that start from a ready repository. */
@Component
public class TestIndexer {

	private final ChunkStore store;

	private final EmbeddingService embeddings;

	private final RepoDao repos;

	private final IngestJobDao jobs;

	private final JavaChunker chunker = new JavaChunker();

	public TestIndexer(ChunkStore store, EmbeddingService embeddings, RepoDao repos, IngestJobDao jobs) {
		this.store = store;
		this.embeddings = embeddings;
		this.repos = repos;
		this.jobs = jobs;
	}

	/** Chunks, embeds and activates the given {@code path, source} pairs as one generation. */
	public Repo index(String namePrefix, String... pathsAndSources) {
		String name = namePrefix + "-" + UUID.randomUUID();
		Repo created = repos.upsert("acme", name, "https://github.com/acme/" + name + ".git");
		IngestJob job = jobs.create(created.id());
		for (int i = 0; i < pathsAndSources.length; i += 2) {
			store.insert(created.id(), job.id(), chunker.chunk(pathsAndSources[i], pathsAndSources[i + 1]).chunks());
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

	/** A small petclinic-like codebase shared by the retrieval and Q&A tests. */
	public static final String[] PETCLINIC = { "OwnerRepository.java", """
			package org.acme.owner;

			interface OwnerRepository {
				Owner findById(int id);
				List<Owner> findByLastName(String lastName);
			}
			""", "PetValidator.java", """
			package org.acme.pet;

			class PetValidator {
				void validate(Pet pet) {
					if (pet.getBirthDate() == null) {
						throw new IllegalArgumentException("birth date is required");
					}
				}
			}
			""", "VetController.java", """
			package org.acme.vet;

			class VetController {
				String showVetList(int page) {
					if (page < 1) {
						throw new IllegalArgumentException("page must be positive");
					}
					return "vets/vetList";
				}
			}
			""" };

}
