package org.acme.library.book;

import java.util.List;
import java.util.Optional;

public interface BookRepository {

	Optional<Book> findByIsbn(String isbn);

	/** Books by an author, matched case-insensitively, newest first. */
	List<Book> findByAuthor(String author);

	List<Book> findAll();

	Book save(Book book);

	boolean existsByIsbn(String isbn);

}
