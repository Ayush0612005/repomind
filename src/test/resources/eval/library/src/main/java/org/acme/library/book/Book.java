package org.acme.library.book;

import java.time.Year;
import java.util.Objects;

/** A title in the catalogue. Several physical copies may exist; loans reduce {@code availableCopies}. */
public class Book {

	private final String isbn;

	private final String title;

	private final String author;

	private final Year published;

	private int totalCopies;

	private int availableCopies;

	public Book(String isbn, String title, String author, Year published, int copies) {
		this.isbn = Objects.requireNonNull(isbn);
		this.title = Objects.requireNonNull(title);
		this.author = Objects.requireNonNull(author);
		this.published = published;
		this.totalCopies = copies;
		this.availableCopies = copies;
	}

	public boolean isAvailable() {
		return availableCopies > 0;
	}

	/** Takes one copy off the shelf; callers must check {@link #isAvailable()} first. */
	public void checkOutCopy() {
		if (availableCopies == 0) {
			throw new IllegalStateException("No copies of " + isbn + " left");
		}
		availableCopies--;
	}

	public void returnCopy() {
		if (availableCopies == totalCopies) {
			throw new IllegalStateException("All copies of " + isbn + " are already on the shelf");
		}
		availableCopies++;
	}

	public void addCopies(int count) {
		if (count <= 0) {
			throw new IllegalArgumentException("count must be positive");
		}
		totalCopies += count;
		availableCopies += count;
	}

	public String isbn() {
		return isbn;
	}

	public String title() {
		return title;
	}

	public String author() {
		return author;
	}

	public Year published() {
		return published;
	}

	public int totalCopies() {
		return totalCopies;
	}

	public int availableCopies() {
		return availableCopies;
	}

}
