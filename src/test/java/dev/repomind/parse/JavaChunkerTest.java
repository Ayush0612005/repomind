package dev.repomind.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaChunkerTest {

	private static final String PATH = "src/main/java/org/acme/petclinic/owner/OwnerService.java";

	private JavaChunker.Result result;

	private Map<String, CodeChunk> bySymbol;

	@BeforeEach
	void chunkFixture() throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/fixtures/OwnerService.java")) {
			String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			result = new JavaChunker().chunk(PATH, source);
		}
		bySymbol = result.chunks().stream().collect(Collectors.toMap(CodeChunk::symbol, Function.identity()));
	}

	@Test
	void producesOneChunkPerTypeAndNonTrivialCallable() {
		assertThat(result.parsed()).isTrue();
		assertThat(bySymbol).containsOnlyKeys(
				"org.acme.petclinic.owner.OwnerService",
				"OwnerService#OwnerService(OwnerRepository)",
				"OwnerService#findOwner(int)",
				"OwnerService#findByLastName(String)",
				"org.acme.petclinic.owner.OwnerService.Validation",
				"OwnerService.Validation#Validation()",
				"org.acme.petclinic.owner.OwnerService.OwnerRepository",
				"org.acme.petclinic.owner.OwnerService.Status");
	}

	@Test
	void classifiesTypeKinds() {
		assertThat(bySymbol.get("org.acme.petclinic.owner.OwnerService").kind()).isEqualTo(ChunkKind.CLASS);
		assertThat(bySymbol.get("org.acme.petclinic.owner.OwnerService.Validation").kind()).isEqualTo(ChunkKind.RECORD);
		assertThat(bySymbol.get("org.acme.petclinic.owner.OwnerService.OwnerRepository").kind())
			.isEqualTo(ChunkKind.INTERFACE);
		assertThat(bySymbol.get("org.acme.petclinic.owner.OwnerService.Status").kind()).isEqualTo(ChunkKind.ENUM);
		assertThat(bySymbol.get("OwnerService#OwnerService(OwnerRepository)").kind()).isEqualTo(ChunkKind.CONSTRUCTOR);
		assertThat(bySymbol.get("OwnerService#findOwner(int)").kind()).isEqualTo(ChunkKind.METHOD);
	}

	@Test
	void methodChunkKeepsOriginalSourceJavadocAndExactLines() {
		CodeChunk findOwner = bySymbol.get("OwnerService#findOwner(int)");

		assertThat(findOwner.startLine()).isEqualTo(19); // Javadoc start
		assertThat(findOwner.endLine()).isEqualTo(25);
		assertThat(findOwner.content()).startsWith("\t/**").contains("lookups++;").endsWith("\t}");
		assertThat(findOwner.citation()).isEqualTo(PATH + ":19-25");
		assertThat(findOwner.packageName()).isEqualTo("org.acme.petclinic.owner");
		assertThat(findOwner.typeName()).isEqualTo("OwnerService");
	}

	@Test
	void typeChunkIsSkeletonWithoutBodiesOrNestedTypes() {
		CodeChunk type = bySymbol.get("org.acme.petclinic.owner.OwnerService");

		assertThat(type.startLine()).isEqualTo(6);
		assertThat(type.endLine()).isEqualTo(65);
		assertThat(type.content())
			.startsWith("package org.acme.petclinic.owner;")
			.contains("Application service for pet owners")
			.contains("private final OwnerRepository repository;")
			.contains("public Optional<Owner> findOwner(int id);")
			.doesNotContain("lookups++")
			.doesNotContain("record Validation")
			.doesNotContain("ARCHIVED");
	}

	@Test
	void skipsTrivialAccessorsAndBodilessInterfaceMethods() {
		assertThat(bySymbol.keySet()).noneMatch(s -> s.contains("getLookups") || s.contains("setLookups"));
		assertThat(bySymbol.keySet()).noneMatch(s -> s.startsWith("OwnerService.OwnerRepository#"));
	}

	@Test
	void searchTextSplitsCamelCaseIdentifiers() {
		CodeChunk byLastName = bySymbol.get("OwnerService#findByLastName(String)");

		assertThat(byLastName.searchText().split(" "))
			.contains("findbylastname", "last", "name", "findbylastnamestartingwith", "starting");
		assertThat(byLastName.contentHash()).hasSize(64);
	}

	@Test
	void countsNonBlankLinesOfCode() {
		assertThat(result.linesOfCode()).isEqualTo(46);
	}

	@Test
	void splitsOversizedMethodsIntoOverlappingWindows() {
		StringBuilder body = new StringBuilder("class Big {\n  void huge() {\n");
		for (int i = 0; i < 300; i++) {
			body.append("    System.out.println(\"line number ").append(i).append(" of a very long method\");\n");
		}
		body.append("  }\n}\n");

		var options = new JavaChunker.Options(400, 80, 5, true);
		List<CodeChunk> parts = new JavaChunker(options).chunk("Big.java", body.toString())
			.chunks()
			.stream()
			.filter(c -> c.kind() == ChunkKind.METHOD)
			.toList();

		assertThat(parts).hasSizeGreaterThan(1);
		assertThat(parts.getFirst().startLine()).isEqualTo(2);
		assertThat(parts.getLast().endLine()).isEqualTo(303);
		for (int i = 1; i < parts.size(); i++) {
			assertThat(parts.get(i).startLine()).isLessThanOrEqualTo(parts.get(i - 1).endLine()); // overlap
		}
		assertThat(parts).allSatisfy(p -> assertThat(p.symbol()).startsWith("Big#huge() [part "));
	}

	@Test
	void fallsBackToLineFragmentsWhenSourceDoesNotParse() {
		String broken = "public class Broken {\n  void x( {\n  }\n" + "// filler\n".repeat(150);

		JavaChunker.Result fallback = new JavaChunker().chunk("Broken.java", broken);

		assertThat(fallback.parsed()).isFalse();
		assertThat(fallback.chunks()).isNotEmpty().allSatisfy(c -> assertThat(c.kind()).isEqualTo(ChunkKind.FRAGMENT));
		assertThat(fallback.chunks().getFirst().startLine()).isEqualTo(1);
		assertThat(fallback.chunks().getLast().endLine()).isEqualTo(154);
	}

}
