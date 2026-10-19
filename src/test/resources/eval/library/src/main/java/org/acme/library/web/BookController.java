package org.acme.library.web;

import java.time.Year;
import java.util.List;

import org.acme.library.book.Book;
import org.acme.library.book.BookService;
import org.acme.library.search.CatalogSearch;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/books")
public class BookController {

	public record NewBook(String isbn, String title, String author, Integer year, int copies) {
	}

	private final BookService books;

	private final CatalogSearch search;

	public BookController(BookService books, CatalogSearch search) {
		this.books = books;
		this.search = search;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Book add(@RequestBody NewBook request) {
		return books.addBook(request.isbn(), request.title(), request.author(),
				request.year() == null ? null : Year.of(request.year()), request.copies());
	}

	@GetMapping("/{isbn}")
	Book get(@PathVariable String isbn) {
		return books.get(isbn);
	}

	/** Title search tolerant of typos, e.g. "hobit" finds "The Hobbit". */
	@GetMapping
	List<Book> search(@RequestParam("q") String query, @RequestParam(defaultValue = "20") int limit) {
		return search.searchTitles(query, Math.min(limit, 100));
	}

}
