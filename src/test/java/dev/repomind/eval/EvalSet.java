package dev.repomind.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.repomind.search.SearchHit;
import tools.jackson.databind.json.JsonMapper;

/** The question set in {@code src/test/resources/eval/questions.json}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalSet(String description, List<Question> questions, Map<String, Thresholds> thresholds,
		List<Unanswerable> unanswerable) {

	/** Minimum scores per search mode; the build fails below them. */
	public record Thresholds(double recallAt5, double mrr) {
	}

	/** About something the fixture does not contain: a correct answer says the code does not show it. */
	public record Unanswerable(String id, String question) {
	}

	/**
	 * @param facts details a correct answer states; each inner list holds acceptable spellings,
	 * matched case-insensitively
	 */
	public record Question(String id, String category, String question, List<String> expect,
			List<List<String>> facts) {

		/** How many of this question's facts the answer states. */
		public int factsStated(String answer) {
			String text = answer.toLowerCase(Locale.ROOT);
			return (int) facts.stream()
				.filter(spellings -> spellings.stream().anyMatch(s -> text.contains(s.toLowerCase(Locale.ROOT))))
				.count();
		}

		public List<Target> targets() {
			return expect.stream().map(Target::parse).toList();
		}

		/** 1-based rank of the first hit matching any expected target, or 0 if none matches. */
		public int firstRelevantRank(List<SearchHit> hits) {
			List<Target> targets = targets();
			for (int i = 0; i < hits.size(); i++) {
				SearchHit hit = hits.get(i);
				if (targets.stream().anyMatch(t -> t.matches(hit.filePath(), hit.symbol()))) {
					return i + 1;
				}
			}
			return 0;
		}

	}

	/**
	 * {@code File.java} matches any chunk of that file; {@code File.java#member} matches a method or
	 * constructor named {@code member} ({@code Type#member(...)}) or a type whose symbol ends in
	 * {@code .member} (nested types are {@code Outer.Inner}).
	 */
	public record Target(String file, String member) {

		static Target parse(String spec) {
			int hash = spec.indexOf('#');
			return hash < 0 ? new Target(spec, null) : new Target(spec.substring(0, hash), spec.substring(hash + 1));
		}

		public boolean matches(String filePath, String symbol) {
			String path = filePath.replace('\\', '/');
			if (!path.equals(file) && !path.endsWith("/" + file)) {
				return false;
			}
			if (member == null) {
				return true;
			}
			return symbol.contains("#" + member + "(") || symbol.endsWith("." + member) || symbol.equals(member);
		}

	}

	public static EvalSet load(String resource) {
		try (InputStream in = EvalSet.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalArgumentException("No eval set at " + resource);
			}
			return JsonMapper.builder().build().readValue(in, EvalSet.class);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
