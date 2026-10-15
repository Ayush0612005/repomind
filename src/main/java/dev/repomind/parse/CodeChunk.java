package dev.repomind.parse;

/**
 * A retrievable unit of code. Line numbers are 1-based and inclusive, and refer to the original
 * file so answers can cite {@code filePath:startLine-endLine}.
 *
 * @param symbol human-readable identity, e.g. {@code org.acme.OwnerController} for a type or
 * {@code OwnerController#findOwner(int)} for a method
 * @param content the text that is embedded and shown to the LLM; for types this is a skeleton
 * (signatures without bodies), for methods the original source
 * @param searchText identifiers split on camelCase, used for the full-text index
 * @param tokenCount approximate token count (characters / 4)
 */
public record CodeChunk(
		String filePath,
		ChunkKind kind,
		String packageName,
		String typeName,
		String symbol,
		int startLine,
		int endLine,
		String content,
		String contentHash,
		String searchText,
		int tokenCount) {

	/** Text sent to the embedding model: a short header gives the vector its location context. */
	public String embeddingText() {
		return "// " + filePath + "\n// " + kind.name().toLowerCase() + " " + symbol + "\n" + content;
	}

	public String citation() {
		return filePath + ":" + startLine + "-" + endLine;
	}

}
