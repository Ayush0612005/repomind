package org.acme.library.book;

import java.time.Year;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class BookService {

	private final BookRepository books;

	public BookService(BookRepository books) {
		this.books = books;
	}

	/**
	 * Adds a title to the catalogue, or more copies if the ISBN is already known. Rejects invalid
	 * ISBNs and publication years in the future.
	 */
	public Book addBook(String isbn, String title, String author, Year published, int copies) {
		if (!IsbnValidator.isValid(isbn)) {
			throw new InvalidBookException("Invalid ISBN-13: " + isbn);
		}
		if (published != null && published.isAfter(Year.now())) {
			throw new InvalidBookException("Publication year " + published + " is in the future");
		}
		String normalized = IsbnValidator.normalize(isbn);
		return books.findByIsbn(normalized).map(existing -> {
			existing.addCopies(copies);
			return books.save(existing);
		}).orElseGet(() -> books.save(new Book(normalized, title.strip(), author.strip(), published, copies)));
	}

	public Book get(String isbn) {
		return books.findByIsbn(IsbnValidator.normalize(isbn))
			.orElseThrow(() -> new BookNotFoundException(isbn));
	}

	public List<Book> byAuthor(String author) {
		return books.findByAuthor(author.strip());
	}

	public static class InvalidBookException extends RuntimeException {

		public InvalidBookException(String message) {
			super(message);
		}

	}

	public static class BookNotFoundException extends RuntimeException {

		public BookNotFoundException(String isbn) {
			super("No book with ISBN " + isbn);
		}

	}

}
