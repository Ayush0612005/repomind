package dev.repomind.index;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/** Embeds documents and queries with the configured model and validates the output shape. */
@Service
public class EmbeddingService {

	private final EmbeddingModel model;

	private final EmbeddingProperties properties;

	private final Timer documentTimer;

	private final Timer queryTimer;

	public EmbeddingService(EmbeddingModel model, EmbeddingProperties properties, MeterRegistry meters) {
		this.model = model;
		this.properties = properties;
		this.documentTimer = Timer.builder("repomind.embedding.latency").tag("type", "document").register(meters);
		this.queryTimer = Timer.builder("repomind.embedding.latency").tag("type", "query").register(meters);
	}

	public List<float[]> embedDocuments(List<String> texts) {
		List<String> prefixed = texts.stream().map(t -> properties.documentPrefix() + t).toList();
		List<float[]> vectors = documentTimer.record(() -> model.embed(prefixed));
		if (vectors == null || vectors.size() != texts.size()) {
			throw new IllegalStateException("Embedding model returned " + (vectors == null ? 0 : vectors.size())
					+ " vectors for " + texts.size() + " inputs");
		}
		vectors.forEach(this::checkDimensions);
		return vectors;
	}

	public float[] embedQuery(String query) {
		float[] vector = queryTimer.record(() -> model.embed(properties.queryPrefix() + query));
		checkDimensions(vector);
		return vector;
	}

	private void checkDimensions(float[] vector) {
		if (vector.length != properties.dimensions()) {
			throw new IllegalStateException("Embedding has " + vector.length + " dimensions but the index expects "
					+ properties.dimensions() + "; the configured embedding model does not match the schema");
		}
	}

}
