package dev.repomind.ingest;

public enum JobStatus {
	QUEUED, CLONING, PARSING, EMBEDDING, COMPLETED, FAILED;

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}
}
