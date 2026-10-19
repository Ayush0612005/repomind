package dev.repomind.limits;

/**
 * Spending guard passed into code that calls a model. {@link #reserve()} runs immediately before
 * a model call, so work that never reaches the model (cache hits, empty retrievals) is free even
 * when the budget is exhausted; {@link #charge(long)} records what the call actually used.
 */
public interface ModelBudget {

	ModelBudget UNLIMITED = new ModelBudget() {

		@Override
		public void reserve() {
		}

		@Override
		public void charge(long tokens) {
		}

	};

	/** @throws BudgetExceededException if nothing is left to spend today */
	void reserve();

	void charge(long tokens);

}
