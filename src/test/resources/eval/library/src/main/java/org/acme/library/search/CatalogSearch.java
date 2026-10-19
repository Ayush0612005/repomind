package org.acme.library.search;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.acme.library.book.Book;
import org.acme.library.book.BookRepository;
import org.springframework.stereotype.Component;

/**
 * Typo-tolerant title search. Each title word is compared to each query word with Levenshtein
 * edit distance; a word matches when the distance is at most one edit per four characters.
 */
@Component
public class CatalogSearch {

	private final BookRepository books;

	public CatalogSearch(BookRepository books) {
		this.books = books;
	}

	public List<Book> searchTitles(String query, int limit) {
		String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
		return books.findAll()
			.stream()
			.filter(book -> matchesAll(book.title().toLowerCase(Locale.ROOT).split("\\W+"), terms))
			.sorted(Comparator.comparing(Book::title))
			.limit(limit)
			.toList();
	}

	private static boolean matchesAll(String[] words, String[] terms) {
		for (String term : terms) {
			boolean found = false;
			for (String word : words) {
				if (levenshtein(word, term) <= Math.max(1, term.length() / 4)) {
					found = true;
					break;
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}

	/** Classic dynamic-programming edit distance using two rolling rows. */
	static int levenshtein(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}

}
