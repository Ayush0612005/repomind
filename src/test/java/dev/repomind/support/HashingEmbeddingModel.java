package dev.repomind.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.repomind.parse.Identifiers;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic bag-of-words embeddings via the hashing trick: texts sharing identifiers or words
 * get similar vectors. Lets integration tests exercise real pgvector similarity search without a
 * model server or GPU.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

	private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

	private final int dimensions;

	private final AtomicInteger embeddedTexts = new AtomicInteger();

	public HashingEmbeddingModel(int dimensions) {
		this.dimensions = dimensions;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<Embedding> out = new ArrayList<>();
		List<String> inputs = request.getInstructions();
		embeddedTexts.addAndGet(inputs.size());
		for (int i = 0; i < inputs.size(); i++) {
			out.add(new Embedding(vectorize(inputs.get(i)), i));
		}
		return new EmbeddingResponse(out);
	}

	@Override
	public float[] embed(Document document) {
		return vectorize(document.getText());
	}

	@Override
	public int dimensions() {
		return dimensions;
	}

	/** Total number of texts embedded so far, i.e. what a paid API would bill for. */
	public int embeddedTexts() {
		return embeddedTexts.get();
	}

	float[] vectorize(String text) {
		float[] v = new float[dimensions];
		Matcher m = WORD.matcher(text);
		while (m.find()) {
			String word = m.group();
			add(v, word.toLowerCase(Locale.ROOT));
			for (String part : Identifiers.split(word)) {
				add(v, part);
			}
		}
		double norm = 0;
		for (float x : v) {
			norm += x * x;
		}
		if (norm == 0) {
			v[0] = 1;
			return v;
		}
		float inv = (float) (1 / Math.sqrt(norm));
		for (int i = 0; i < v.length; i++) {
			v[i] *= inv;
		}
		return v;
	}

	private void add(float[] v, String token) {
		if (token.equals("search") || token.equals("document") || token.equals("query")) {
			return; // ignore the model task prefixes
		}
		int h = token.hashCode();
		v[Math.floorMod(h, dimensions)] += (h & 1) == 0 ? 1 : -1;
	}

}
