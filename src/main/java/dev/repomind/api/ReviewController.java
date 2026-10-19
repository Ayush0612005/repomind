package dev.repomind.api;

import java.util.UUID;

import dev.repomind.repo.RepoDao;
import dev.repomind.review.Review;
import dev.repomind.review.ReviewService;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

	private final ReviewService reviews;

	private final RepoDao repos;

	public ReviewController(ReviewService reviews, RepoDao repos) {
		this.reviews = reviews;
		this.repos = repos;
	}

	/**
	 * Generates a review of the pull request using the repository's index for context. Nothing is
	 * posted to GitHub; the comments are returned for inspection.
	 */
	@PostMapping("/api/repos/{id}/pulls/{number}/review")
	Review review(@PathVariable UUID id, @PathVariable @Min(1) int number) {
		var repo = repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
		return reviews.review(repo, number);
	}

}
