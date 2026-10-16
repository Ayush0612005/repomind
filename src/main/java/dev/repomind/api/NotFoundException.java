package dev.repomind.api;

public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}
