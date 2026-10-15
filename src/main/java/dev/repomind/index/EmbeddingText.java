package dev.repomind.index;

import java.util.Locale;

import dev.repomind.parse.CodeChunk;

/**
 * The exact text sent to the embedding model for a chunk. A short location header gives the vector
 * context the raw code lacks (which file, which class), which measurably helps retrieval for
 * questions phrased in terms of components rather than identifiers.
 */
public final class EmbeddingText {

	private EmbeddingText() {
	}

	public static String of(CodeChunk chunk) {
		return of(chunk.filePath(), chunk.kind().name(), chunk.symbol(), chunk.content());
	}

	public static String of(String filePath, String kind, String symbol, String content) {
		return "// " + filePath + "\n// " + kind.toLowerCase(Locale.ROOT) + " " + symbol + "\n" + content;
	}

}
