package dev.repomind.review;

import org.springframework.http.HttpStatus;

/** A webhook delivery that must not be processed: unsigned, wrongly signed, or unreadable. */
public class WebhookRejectedException extends RuntimeException {

	private final HttpStatus status;

	public WebhookRejectedException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}

}
