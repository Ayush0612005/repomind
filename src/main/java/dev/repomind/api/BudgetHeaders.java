package dev.repomind.api;

import java.util.OptionalLong;

import dev.repomind.limits.ApiClient;
import dev.repomind.limits.TokenBudget;
import org.springframework.http.ResponseEntity;

/** Tells clients of model-backed endpoints how much of today's token budget is left. */
final class BudgetHeaders {

	private BudgetHeaders() {
	}

	static <B extends ResponseEntity.HeadersBuilder<B>> B with(B response, TokenBudget budgets, ApiClient client) {
		if (client.unlimited()) {
			return response;
		}
		OptionalLong remaining = budgets.remaining(client);
		if (remaining.isPresent()) {
			response.header("X-Token-Budget-Remaining", String.valueOf(Math.max(0, remaining.getAsLong())));
		}
		return response;
	}

}
