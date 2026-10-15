package dev.repomind.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns source text into full-text search terms. Postgres' parser treats {@code findOwnerById}
 * as one opaque word, so identifiers are emitted both whole and split into their camelCase /
 * snake_case parts, letting a query for "owner" match {@code findOwnerById}.
 */
public final class Identifiers {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

	private static final Pattern WORD_BOUNDARY = Pattern
		.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_$]+");

	private static final Set<String> NOISE = Set.of("abstract", "boolean", "break", "byte", "case", "catch",
			"char", "class", "continue", "default", "do", "double", "else", "enum", "extends", "final",
			"finally", "float", "for", "if", "implements", "import", "instanceof", "int", "interface", "long",
			"new", "null", "package", "private", "protected", "public", "return", "short", "static", "super",
			"switch", "this", "throw", "throws", "true", "false", "try", "var", "void", "while", "java", "src",
			"main", "string", "override", "the", "a", "an", "of", "to", "and", "or", "is", "in");

	private Identifiers() {
	}

	/** Splits one identifier into lower-case words: {@code parseHTTPResponse} → parse, http, response. */
	public static List<String> split(String identifier) {
		List<String> words = new ArrayList<>();
		for (String part : WORD_BOUNDARY.split(identifier)) {
			if (part.length() > 1) {
				words.add(part.toLowerCase(Locale.ROOT));
			}
		}
		return words;
	}

	public static String searchText(String... texts) {
		StringBuilder out = new StringBuilder();
		for (String text : texts) {
			if (text == null) {
				continue;
			}
			Matcher m = IDENTIFIER.matcher(text);
			while (m.find()) {
				String ident = m.group();
				String lower = ident.toLowerCase(Locale.ROOT);
				if (lower.length() < 2 || NOISE.contains(lower)) {
					continue;
				}
				out.append(lower).append(' ');
				List<String> words = split(ident);
				if (words.size() > 1) {
					for (String word : words) {
						if (!NOISE.contains(word)) {
							out.append(word).append(' ');
						}
					}
				}
			}
		}
		return out.toString().trim();
	}

}
