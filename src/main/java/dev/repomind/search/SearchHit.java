package dev.repomind.search;

/**
 * One retrieved chunk. {@code vectorRank} / {@code keywordRank} are 1-based positions in each
 * retriever's list, or 0 when that retriever did not return the chunk; they make it visible in the
 * API why a result ranked where it did.
 */
public record SearchHit(
		long chunkId,
		String filePath,
		String kind,
		String symbol,
		int startLine,
		int endLine,
		String content,
		double score,
		int vectorRank,
		int keywordRank) {

	public String citation() {
		return filePath + ":" + startLine + "-" + endLine;
	}

}
