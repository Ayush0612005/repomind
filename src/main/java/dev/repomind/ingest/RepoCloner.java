package dev.repomind.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

/** Shallow-clones a repository's default branch with JGit (no git binary needed on the host). */
@Component
public class RepoCloner {

	private static final Logger log = LoggerFactory.getLogger(RepoCloner.class);

	public record Checkout(Path directory, String branch, String commitSha) {
	}

	private final IngestProperties properties;

	public RepoCloner(IngestProperties properties) {
		this.properties = properties;
	}

	public Checkout cloneShallow(UUID jobId, String cloneUrl) {
		Path dir = properties.workdir().resolve(jobId.toString());
		try {
			Files.createDirectories(dir.getParent());
			delete(dir); // leftover from a crashed attempt
		}
		catch (IOException ex) {
			throw new IngestException("Cannot prepare clone directory " + dir, ex);
		}

		try (Git git = Git.cloneRepository()
			.setURI(cloneUrl)
			.setDirectory(dir.toFile())
			.setDepth(1)
			.setCloneAllBranches(false)
			.setTimeout((int) properties.cloneTimeout().toSeconds())
			.call()) {
			Repository repository = git.getRepository();
			ObjectId head = repository.resolve("HEAD");
			if (head == null) {
				throw new IngestException("Repository " + cloneUrl + " is empty");
			}
			return new Checkout(dir, repository.getBranch(), head.name());
		}
		catch (GitAPIException | IOException ex) {
			delete(dir);
			throw new IngestException("Clone failed for " + cloneUrl + ": " + ex.getMessage(), ex);
		}
	}

	/** Git writes pack files read-only, which blocks deletion on Windows; clear the flag first. */
	public void delete(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.map(Path::toFile).filter(f -> !f.canWrite()).forEach(f -> f.setWritable(true));
			FileSystemUtils.deleteRecursively(dir);
		}
		catch (IOException ex) {
			log.warn("Could not delete clone directory {}: {}", dir, ex.getMessage());
		}
	}

}
