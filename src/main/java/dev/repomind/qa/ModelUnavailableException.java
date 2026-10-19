package dev.repomind.qa;

/** The chat model could not be reached or returned no answer. */
public class ModelUnavailableException extends RuntimeException {

	public ModelUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
