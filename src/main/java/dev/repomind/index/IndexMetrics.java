package dev.repomind.index;

import java.util.Locale;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How much is indexed, for the operations dashboard. Gauges are read on each scrape with cheap
 * counts over the active generations only.
 */
@Component
public class IndexMetrics implements MeterBinder {

	private final JdbcClient jdbc;

	public IndexMetrics(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		for (String status : new String[] { "PENDING", "INGESTING", "READY", "FAILED" }) {
			Gauge.builder("repomind.repositories", () -> countByStatus(status))
				.tag("status", status.toLowerCase(Locale.ROOT))
				.description("Tracked repositories by status")
				.register(registry);
		}
		Gauge.builder("repomind.index.chunks", () -> count("""
				SELECT count(*) FROM code_chunks c JOIN repositories r ON c.ingest_job_id = r.active_job_id
				"""))
			.description("Chunks in the generations currently served to queries")
			.register(registry);
		Gauge.builder("repomind.index.lines", () -> count("""
				SELECT coalesce(sum(j.lines_of_code), 0) FROM ingest_jobs j JOIN repositories r ON j.id = r.active_job_id
				"""))
			.description("Lines of code in the indexed generations")
			.register(registry);
		Gauge.builder("repomind.answer.cache.entries", () -> count("SELECT count(*) FROM answer_cache"))
			.description("Answers in the semantic cache")
			.register(registry);
	}

	private double countByStatus(String status) {
		try {
			return jdbc.sql("SELECT count(*) FROM repositories WHERE status = :status")
				.param("status", status)
				.query(Long.class)
				.single();
		}
		catch (RuntimeException ex) {
			return Double.NaN;
		}
	}

	private double count(String sql) {
		try {
			return jdbc.sql(sql).query(Long.class).single();
		}
		catch (RuntimeException ex) {
			return Double.NaN; // a scrape must never fail because the database is briefly unavailable
		}
	}

}
