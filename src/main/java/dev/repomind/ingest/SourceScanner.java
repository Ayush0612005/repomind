package dev.repomind.ingest;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/** Finds the Java sources worth indexing in a checkout, skipping build output and VCS metadata. */
@Component
public class SourceScanner {

	private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", ".github", ".idea", ".gradle", ".mvn",
			"target", "build", "out", "bin", "node_modules", "generated", "generated-sources");

	public record SourceFile(String path, String content) {
	}

	public record Scan(List<SourceFile> files, int skippedTooLarge, boolean truncated) {
	}

	private final IngestProperties properties;

	public SourceScanner(IngestProperties properties) {
		this.properties = properties;
	}

	public Scan scan(Path root) throws IOException {
		List<Path> candidates = new ArrayList<>();
		int[] tooLarge = { 0 };
		Files.walkFileTree(root, new SimpleFileVisitor<>() {

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (dir.equals(root)) {
					return FileVisitResult.CONTINUE;
				}
				return SKIPPED_DIRECTORIES.contains(dir.getFileName().toString()) || isTestResources(dir)
						? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
					if (attrs.size() > properties.maxFileBytes()) {
						tooLarge[0]++;
					}
					else {
						candidates.add(file);
					}
				}
				return FileVisitResult.CONTINUE;
			}

		});

		candidates.sort(Comparator.comparing(Path::toString));
		boolean truncated = candidates.size() > properties.maxFiles();
		List<SourceFile> files = new ArrayList<>();
		for (Path file : candidates.subList(0, Math.min(candidates.size(), properties.maxFiles()))) {
			String relative = root.relativize(file).toString().replace('\\', '/');
			files.add(new SourceFile(relative, read(file)));
		}
		return new Scan(files, tooLarge[0], truncated);
	}

	/**
	 * {@code src/test/resources} (Maven/Gradle layout): .java files there are fixtures that tests
	 * read as data, not code of the project, and indexing them makes answers describe the fixture.
	 */
	static boolean isTestResources(Path dir) {
		Path parent = dir.getParent();
		return dir.getFileName().toString().equals("resources") && parent != null
				&& parent.getFileName() != null && parent.getFileName().toString().equals("test");
	}

	private static String read(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException ex) {
			text = new String(bytes, StandardCharsets.ISO_8859_1); // legacy-encoded sources
		}
		return text.startsWith("﻿") ? text.substring(1) : text;
	}

}
