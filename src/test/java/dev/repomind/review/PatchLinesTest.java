package dev.repomind.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PatchLinesTest {

	static final String VET_PATCH = """
			@@ -3,7 +3,9 @@ package org.acme.vet;
			 class VetController {
			 	String showVetList(int page) {
			-		if (page < 1) {
			+		if (page < 0) {
			 			throw new IllegalArgumentException("page must be positive");
			 		}
			+		// TODO cache
			+		log(page);
			 		return "vets/vetList";
			 	}
			""";

	@Test
	void mapsAddedAndContextLinesToNewFileNumbers() {
		PatchLines lines = PatchLines.parse(VET_PATCH);

		assertThat(lines.addedLines()).containsExactly(5, 8, 9);
		for (int line = 3; line <= 11; line++) {
			assertThat(lines.isCommentable(line)).as("line %d", line).isTrue();
		}
		assertThat(lines.isCommentable(2)).isFalse();
		assertThat(lines.isCommentable(12)).isFalse(); // no phantom line from the trailing newline
		assertThat(lines.addedText()).isEqualTo("\t\tif (page < 0) {\n\t\t// TODO cache\n\t\tlog(page);\n");
	}

	@Test
	void annotatesTheDiffWithNumbersOnlyWhereCommentsAreAllowed() {
		String annotated = PatchLines.parse(VET_PATCH).annotated();

		assertThat(annotated).startsWith("@@ -3,7 +3,9 @@ package org.acme.vet;\n      3 | class VetController {")
			.contains("-       | \t\tif (page < 1) {\n+     5 | \t\tif (page < 0) {")
			.endsWith("     11 | \t}")
			.doesNotContain("\r");
	}

	@Test
	void restartsNumberingAtEachHunkAndIgnoresNoNewlineMarkers() {
		PatchLines lines = PatchLines.parse("""
				@@ -1,2 +1,2 @@
				-a
				+b
				 c
				@@ -40 +40,2 @@
				 x
				+y
				\\ No newline at end of file""");

		assertThat(lines.addedLines()).containsExactly(1, 41);
		assertThat(lines.isCommentable(2)).isTrue();
		assertThat(lines.isCommentable(40)).isTrue();
		assertThat(lines.isCommentable(42)).isFalse();
		assertThat(lines.isCommentable(3)).isFalse();
	}

	@Test
	void newFilePatchStartsAtLineOne() {
		PatchLines lines = PatchLines.parse("@@ -0,0 +1,3 @@\n+class A {\n+}\n+\n");

		assertThat(lines.addedLines()).containsExactly(1, 2, 3);
	}

}
