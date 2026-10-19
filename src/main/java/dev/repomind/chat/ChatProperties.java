package dev.repomind.chat;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param order providers to try, most preferred first; configured providers not listed are tried last
 * @param circuitBreaker when a provider is considered down and for how long it is skipped
 */
@ConfigurationProperties("repomind.chat")
public record ChatProperties(
		@DefaultValue({ "anthropic", "ollama" }) List<String> order,
		@DefaultValue CircuitBreaker circuitBreaker) {

	/**
	 * @param failureRateThreshold percentage of failed calls in the window that opens the circuit
	 * @param slidingWindowSize calls the failure rate is computed over
	 * @param minimumCalls calls needed before the failure rate is evaluated
	 * @param openFor how long an open circuit skips the provider before letting a probe call through
	 * @param halfOpenCalls probe calls allowed while half-open
	 */
	public record CircuitBreaker(
			@DefaultValue("50") float failureRateThreshold,
			@DefaultValue("10") int slidingWindowSize,
			@DefaultValue("4") int minimumCalls,
			@DefaultValue("30s") Duration openFor,
			@DefaultValue("1") int halfOpenCalls) {
	}

}
