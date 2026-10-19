package dev.repomind.qa;

import java.util.List;

/**
 * @param citations the sources the answer actually cites, in order of first mention
 * @param sources every source that was shown to the model
 * @param model the model that answered, when the provider reports it
 */
public record Answer(String answer, List<Source> citations, List<Source> sources, String model, long latencyMs) {

	/** @param index the number the model uses to cite this source, e.g. {@code [2]} */
	public record Source(int index, String location, String symbol) {
	}

}
