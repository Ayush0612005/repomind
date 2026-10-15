package dev.repomind.parse;

public enum ChunkKind {
	CLASS, INTERFACE, ENUM, RECORD, ANNOTATION,
	METHOD, CONSTRUCTOR,
	/** Line-window chunk used when a file cannot be parsed into an AST. */
	FRAGMENT;

	public boolean isType() {
		return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD || this == ANNOTATION;
	}
}
