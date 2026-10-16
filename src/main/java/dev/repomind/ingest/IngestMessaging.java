package dev.repomind.ingest;

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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka wiring for ingestion. Messages carry only the job id (keyed by repository id so jobs for
 * one repository stay ordered on one partition); Postgres stays the source of truth for job state.
 */
@Configuration(proxyBeanMethods = false)
public class IngestMessaging {

	public static final String TOPIC = "repomind.ingest.requests";

	@Bean
	NewTopic ingestTopic() {
		return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
	}

	/**
	 * Pipeline failures are recorded on the job and never thrown; anything that does escape (e.g.
	 * the database is briefly unreachable while claiming) is retried a few times, then logged. The
	 * sweeper re-publishes jobs left QUEUED, so nothing is lost permanently.
	 */
	@Bean
	DefaultErrorHandler ingestErrorHandler() {
		return new DefaultErrorHandler(new FixedBackOff(5_000, 3));
	}

	@Component
	public static class Publisher {

		private static final Logger log = LoggerFactory.getLogger(Publisher.class);

		private final KafkaTemplate<String, String> kafka;

		public Publisher(KafkaTemplate<String, String> kafka) {
			this.kafka = kafka;
		}

		/** @return false if the broker did not acknowledge; the sweeper will retry */
		public boolean publish(UUID repositoryId, UUID jobId) {
			try {
				kafka.send(TOPIC, repositoryId.toString(), jobId.toString()).get(10, TimeUnit.SECONDS);
				return true;
			}
			catch (Exception ex) {
				if (ex instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				log.warn("Could not publish ingest job {}: {}", jobId, ex.getMessage());
				return false;
			}
		}

	}

	@Component
	public static class Listener {

		private final IngestionPipeline pipeline;

		public Listener(IngestionPipeline pipeline) {
			this.pipeline = pipeline;
		}

		@KafkaListener(id = "ingest-worker", topics = TOPIC, groupId = "repomind-ingest")
		void onIngestRequested(String jobId) {
			pipeline.run(UUID.fromString(jobId));
		}

	}

}
