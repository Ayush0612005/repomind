package dev.repomind.review;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One file's unified diff (GitHub's {@code patch} field: hunks only, no file headers), resolved to
 * new-file line numbers. GitHub only accepts review comments on lines that appear in the diff, so
 * the model is shown the diff with those numbers and its comments are checked against
 * {@link #isCommentable(int)}.
 */
public final class PatchLines {

	private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

	private final Set<Integer> commentable;

	private final Set<Integer> added;

	private final String annotated;

	private final String addedText;

	private PatchLines(Set<Integer> commentable, Set<Integer> added, String annotated, String addedText) {
		this.commentable = Collections.unmodifiableSet(commentable);
		this.added = Collections.unmodifiableSet(added);
		this.annotated = annotated;
		this.addedText = addedText;
	}

	public static PatchLines parse(String patch) {
		Set<Integer> commentable = new LinkedHashSet<>();
		Set<Integer> added = new LinkedHashSet<>();
		StringBuilder annotated = new StringBuilder();
		StringBuilder addedText = new StringBuilder();
		int newLine = -1;
		String body = patch.endsWith("\n") ? patch.substring(0, patch.length() - 1) : patch;
		for (String line : body.split("\r?\n", -1)) {
			Matcher hunk = HUNK.matcher(line);
			if (hunk.matches()) {
				newLine = Integer.parseInt(hunk.group(2));
				annotated.append(line).append('\n');
				continue;
			}
			if (newLine < 0) {
				continue; // before the first hunk
			}
			char marker = line.isEmpty() ? ' ' : line.charAt(0);
			String text = line.isEmpty() ? "" : line.substring(1);
			switch (marker) {
				case '+' -> {
					annotated.append(numbered('+', newLine, text));
					commentable.add(newLine);
					added.add(newLine);
					addedText.append(text).append('\n');
					newLine++;
				}
				case '-' -> annotated.append(String.format("-%6s | %s", "", text)).append('\n');
				case ' ' -> {
					annotated.append(numbered(' ', newLine, text));
					commentable.add(newLine);
					newLine++;
				}
				default -> {
					// "\ No newline at end of file" and anything unexpected carry no line
				}
			}
		}
		return new PatchLines(commentable, added, annotated.toString().stripTrailing(), addedText.toString());
	}

	private static String numbered(char marker, int line, String text) {
		return String.format("%c%6d | %s", marker, line, text) + "\n";
	}

	/** True for added and unchanged context lines, which GitHub accepts comments on (new-file side). */
	public boolean isCommentable(int line) {
		return commentable.contains(line);
	}

	public Set<Integer> addedLines() {
		return added;
	}

	/** The diff with a new-file line number on every line that can carry a comment. */
	public String annotated() {
		return annotated;
	}

	/** Source text of the added lines, used to retrieve related code. */
	public String addedText() {
		return addedText;
	}

}
