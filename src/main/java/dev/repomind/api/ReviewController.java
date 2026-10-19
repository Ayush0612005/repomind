package dev.repomind.api;

import java.net.URI;
import java.util.UUID;

import dev.repomind.repo.RepoDao;
import dev.repomind.review.Review;
import dev.repomind.review.ReviewJob;
import dev.repomind.review.ReviewJobDao;
import dev.repomind.review.ReviewJobService;
import dev.repomind.review.ReviewService;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

	private final ReviewService reviews;

	private final ReviewJobService reviewJobs;

	private final ReviewJobDao jobs;

	private final RepoDao repos;

	public ReviewController(ReviewService reviews, ReviewJobService reviewJobs, ReviewJobDao jobs, RepoDao repos) {
		this.reviews = reviews;
		this.reviewJobs = reviewJobs;
		this.jobs = jobs;
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

	/**
	 * Queues a review of the pull request's current head commit that is posted to GitHub. Returns 202
	 * with the new job, or 200 with the existing job if that commit was already reviewed.
	 */
	@PostMapping("/api/repos/{id}/pulls/{number}/reviews")
	ResponseEntity<ReviewJob> publish(@PathVariable UUID id, @PathVariable @Min(1) int number) {
		var repo = repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
		ReviewJobService.Submission submission = reviewJobs.submitLatest(repo, number, ReviewJob.Trigger.API);
		var response = submission.created() ? ResponseEntity.accepted() : ResponseEntity.ok();
		return response.location(URI.create("/api/review-jobs/" + submission.job().id())).body(submission.job());
	}

	@GetMapping("/api/review-jobs/{id}")
	ReviewJob job(@PathVariable UUID id) {
		return jobs.findById(id).orElseThrow(() -> new NotFoundException("No review job " + id));
	}

}
