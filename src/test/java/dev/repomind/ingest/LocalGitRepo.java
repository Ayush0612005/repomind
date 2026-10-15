package dev.repomind.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

/** Builds a throwaway git repository on disk so clone tests need no network. */
public final class LocalGitRepo {

	private LocalGitRepo() {
	}

	/** Creates a repo at {@code dir} with one commit containing {@code files} (path → content). */
	public static String create(Path dir, Map<String, String> files) throws IOException, GitAPIException {
		try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
			for (var file : files.entrySet()) {
				Path path = dir.resolve(file.getKey());
				Files.createDirectories(path.getParent());
				Files.writeString(path, file.getValue());
			}
			git.add().addFilepattern(".").call();
			git.commit().setMessage("initial").setAuthor("test", "test@example.com").setSign(false).call();
		}
		return dir.toUri().toString();
	}

}
