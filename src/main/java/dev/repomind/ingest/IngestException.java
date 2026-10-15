package dev.repomind.ingest;

/** A failure that ends an ingestion job; its message is stored on the job for the status API. */
public class IngestException extends RuntimeException {

	public IngestException(String message) {
		super(message);
	}

	public IngestException(String message, Throwable cause) {
		super(message, cause);
	}

}
