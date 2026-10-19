package dev.repomind.review;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka wiring for review jobs, mirroring ingestion: the message is just the job id, keyed by
 * repository, and Postgres holds the job's state. Reviews take tens of seconds of model time, so
 * they run off the request thread and webhook deliveries are acknowledged immediately.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewMessaging {

	public static final String TOPIC = "repomind.review.requests";

	@Bean
	NewTopic reviewTopic() {
		return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
	}

	@Component
	public static class Publisher {

		private static final Logger log = LoggerFactory.getLogger(Publisher.class);

		private final KafkaTemplate<String, String> kafka;

		public Publisher(KafkaTemplate<String, String> kafka) {
			this.kafka = kafka;
		}

		/** @return false if the broker did not acknowledge; the sweeper re-publishes */
		public boolean publish(UUID repositoryId, UUID jobId) {
			try {
				kafka.send(TOPIC, repositoryId.toString(), jobId.toString()).get(10, TimeUnit.SECONDS);
				return true;
			}
			catch (Exception ex) {
				if (ex instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				log.warn("Could not publish review job {}: {}", jobId, ex.getMessage());
				return false;
			}
		}

	}

	@Component
	public static class Listener {

		private final ReviewJobService jobs;

		public Listener(ReviewJobService jobs) {
			this.jobs = jobs;
		}

		@KafkaListener(id = "review-worker", topics = TOPIC, groupId = "repomind-review")
		void onReviewRequested(String jobId) {
			jobs.run(UUID.fromString(jobId));
		}

	}

}
