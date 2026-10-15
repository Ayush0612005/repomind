package dev.repomind.index;

/** pgvector text format helpers: {@code [0.1,0.2,...]}. */
public final class Vectors {

	private Vectors() {
	}

	public static String toLiteral(float[] vector) {
		StringBuilder out = new StringBuilder(vector.length * 10).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				out.append(',');
			}
			float v = vector[i];
			if (!Float.isFinite(v)) {
				throw new IllegalArgumentException("Embedding contains a non-finite value at index " + i);
			}
			out.append(v);
		}
		return out.append(']').toString();
	}

}
