package dev.repomind.ingest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A validated GitHub {@code owner/name}. Accepts {@code owner/name}, {@code github.com/owner/name}
 * and full HTTPS URLs (with or without {@code .git} or trailing path such as {@code /tree/main}).
 */
public record GitHubRepoRef(String owner, String name) {

	private static final Pattern INPUT = Pattern.compile(
			"^(?:(?:https?://)?(?:www\\.)?github\\.com/)?([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?(?:/.*)?/?$");

	public static GitHubRepoRef parse(String input) {
		if (input == null || input.isBlank()) {
			throw new IllegalArgumentException("Repository must not be blank");
		}
		Matcher m = INPUT.matcher(input.trim());
		if (!m.matches() || m.group(2).equals(".") || m.group(2).equals("..")) {
			throw new IllegalArgumentException(
					"Not a GitHub repository: '" + input + "'. Use owner/name or https://github.com/owner/name");
		}
		return new GitHubRepoRef(m.group(1), m.group(2));
	}

	public String cloneUrl() {
		return "https://github.com/" + owner + "/" + name + ".git";
	}

	@Override
	public String toString() {
		return owner + "/" + name;
	}

}
