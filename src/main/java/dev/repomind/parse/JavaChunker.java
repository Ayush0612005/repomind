package dev.repomind.parse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.LocalRecordDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;

/**
 * Splits a Java source file into retrieval chunks along AST boundaries.
 *
 * <ul>
 * <li>One <b>type chunk</b> per class/interface/enum/record: a skeleton with fields and member
 * signatures but no method bodies, so "what does X do" questions retrieve a compact overview.</li>
 * <li>One <b>callable chunk</b> per method/constructor: the original source including Javadoc
 * and annotations, with exact line numbers for citations. Oversized methods are split into
 * overlapping line windows.</li>
 * <li>Trivial getters/setters are skipped: they add noise and are already visible in the type
 * skeleton.</li>
 * <li>Files that fail to parse fall back to line-window {@link ChunkKind#FRAGMENT} chunks, so a
 * syntax JavaParser does not support never drops a file from the index.</li>
 * </ul>
 *
 * Instances are thread-safe: a {@link JavaParser} (which is not) is created per call.
 */
public class JavaChunker {

	public record Options(int maxChunkTokens, int fragmentLines, int overlapLines, boolean skipTrivialAccessors) {

		public static Options defaults() {
			return new Options(1_200, 80, 5, true);
		}

	}

	public record Result(List<CodeChunk> chunks, boolean parsed, int linesOfCode) {
	}

	private final Options options;

	public JavaChunker() {
		this(Options.defaults());
	}

	public JavaChunker(Options options) {
		this.options = options;
	}

	public Result chunk(String filePath, String source) {
		String[] lines = source.split("\\R", -1);
		int loc = countLinesOfCode(lines);

		ParseResult<CompilationUnit> parsed = newParser().parse(source);
		Optional<CompilationUnit> unit = parsed.getResult();
		if (!parsed.isSuccessful() || unit.isEmpty()) {
			return new Result(fragments(filePath, lines), false, loc);
		}

		CompilationUnit cu = unit.get();
		String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
		List<CodeChunk> chunks = new ArrayList<>();

		for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
			if (isLocal(type)) {
				continue; // local classes are part of their enclosing method's chunk
			}
			String typeName = nestedName(type);
			chunks.add(typeChunk(filePath, pkg, typeName, type));

			for (BodyDeclaration<?> member : type.getMembers()) {
				if (member instanceof CallableDeclaration<?> callable) {
					if (!hasBody(callable) || (options.skipTrivialAccessors() && isTrivialAccessor(callable))) {
						continue;
					}
					chunks.addAll(callableChunks(filePath, pkg, typeName, callable, lines));
				}
				else if (member instanceof CompactConstructorDeclaration compact) {
					chunks.addAll(compactConstructorChunks(filePath, pkg, typeName, compact, lines));
				}
			}
		}
		return new Result(chunks, true, loc);
	}

	private static JavaParser newParser() {
		return new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_25)
			.setAttributeComments(true));
	}

	// ---------------------------------------------------------------- type skeletons

	private CodeChunk typeChunk(String filePath, String pkg, String typeName, TypeDeclaration<?> type) {
		TypeDeclaration<?> skeleton = (TypeDeclaration<?>) type.clone();
		skeleton.getMembers().removeIf(m -> m instanceof TypeDeclaration<?> || m instanceof InitializerDeclaration);
		skeleton.findAll(MethodDeclaration.class).forEach(MethodDeclaration::removeBody);
		skeleton.findAll(ConstructorDeclaration.class).forEach(c -> c.setBody(new BlockStmt()));
		skeleton.findAll(CompactConstructorDeclaration.class).forEach(c -> c.setBody(new BlockStmt()));

		String content = (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n") + skeleton;
		int maxChars = options.maxChunkTokens() * 4;
		if (content.length() > maxChars) {
			content = content.substring(0, maxChars) + "\n    // ... skeleton truncated\n}";
		}

		String symbol = pkg.isEmpty() ? typeName : pkg + "." + typeName;
		return build(filePath, typeKind(type), pkg, typeName, symbol, startLine(type), endLine(type), content);
	}

	private static ChunkKind typeKind(TypeDeclaration<?> type) {
		return switch (type) {
			case ClassOrInterfaceDeclaration c when c.isInterface() -> ChunkKind.INTERFACE;
			case EnumDeclaration e -> ChunkKind.ENUM;
			case RecordDeclaration r -> ChunkKind.RECORD;
			case AnnotationDeclaration a -> ChunkKind.ANNOTATION;
			default -> ChunkKind.CLASS;
		};
	}

	// ---------------------------------------------------------------- methods and constructors

	private List<CodeChunk> callableChunks(String filePath, String pkg, String typeName,
			CallableDeclaration<?> callable, String[] lines) {
		String params = callable.getParameters()
			.stream()
			.map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : ""))
			.collect(Collectors.joining(", "));
		String symbol = typeName + "#" + callable.getNameAsString() + "(" + params + ")";
		ChunkKind kind = callable instanceof ConstructorDeclaration ? ChunkKind.CONSTRUCTOR : ChunkKind.METHOD;
		return sourceChunks(filePath, pkg, typeName, symbol, kind, startLine(callable), endLine(callable), lines);
	}

	private List<CodeChunk> compactConstructorChunks(String filePath, String pkg, String typeName,
			CompactConstructorDeclaration ctor, String[] lines) {
		String symbol = typeName + "#" + ctor.getNameAsString() + "()";
		return sourceChunks(filePath, pkg, typeName, symbol, ChunkKind.CONSTRUCTOR, startLine(ctor), endLine(ctor),
				lines);
	}

	/** Takes original source lines (not the pretty-printed AST) so formatting and line numbers match the file. */
	private List<CodeChunk> sourceChunks(String filePath, String pkg, String typeName, String symbol, ChunkKind kind,
			int start, int end, String[] lines) {
		String content = slice(lines, start, end);
		if (estimateTokens(content) <= options.maxChunkTokens()) {
			return List.of(build(filePath, kind, pkg, typeName, symbol, start, end, content));
		}

		int lineCount = end - start + 1;
		int parts = (int) Math.ceil((double) estimateTokens(content) / options.maxChunkTokens());
		int window = Math.max(options.overlapLines() + 1, (int) Math.ceil((double) lineCount / parts));
		List<CodeChunk> chunks = new ArrayList<>();
		int part = 1;
		for (int from = start; from <= end; from += window - options.overlapLines()) {
			int to = Math.min(end, from + window - 1);
			chunks.add(build(filePath, kind, pkg, typeName, symbol + " [part " + part++ + "]", from, to,
					slice(lines, from, to)));
			if (to == end) {
				break;
			}
		}
		return chunks;
	}

	private static boolean hasBody(CallableDeclaration<?> callable) {
		return !(callable instanceof MethodDeclaration m) || m.getBody().isPresent();
	}

	/** {@code getX() { return x; }}, {@code isX() { return x; }} and {@code setX(v) { this.x = v; }}. */
	static boolean isTrivialAccessor(CallableDeclaration<?> callable) {
		if (!(callable instanceof MethodDeclaration m) || m.getBody().isEmpty()) {
			return false;
		}
		List<Statement> statements = m.getBody().get().getStatements();
		if (statements.size() != 1) {
			return false;
		}
		String name = m.getNameAsString();
		Statement only = statements.getFirst();
		boolean getter = name.matches("(get|is)[A-Z].*") && m.getParameters().isEmpty()
				&& only instanceof ReturnStmt ret && ret.getExpression().map(e -> e.isNameExpr() || e.isFieldAccessExpr()).orElse(false);
		boolean setter = name.matches("set[A-Z].*") && m.getParameters().size() == 1
				&& only instanceof ExpressionStmt es && es.getExpression() instanceof AssignExpr assign
				&& assign.getValue().isNameExpr();
		return getter || setter;
	}

	// ---------------------------------------------------------------- unparseable files

	private List<CodeChunk> fragments(String filePath, String[] lines) {
		List<CodeChunk> chunks = new ArrayList<>();
		int step = Math.max(1, options.fragmentLines() - options.overlapLines());
		for (int from = 1; from <= lines.length; from += step) {
			int to = Math.min(lines.length, from + options.fragmentLines() - 1);
			String content = slice(lines, from, to);
			if (!content.isBlank()) {
				chunks.add(build(filePath, ChunkKind.FRAGMENT, "", "", filePath + " [lines " + from + "-" + to + "]",
						from, to, content));
			}
			if (to == lines.length) {
				break;
			}
		}
		return chunks;
	}

	// ---------------------------------------------------------------- helpers

	private static CodeChunk build(String filePath, ChunkKind kind, String pkg, String typeName, String symbol,
			int start, int end, String content) {
		return new CodeChunk(filePath, kind, pkg, typeName, symbol, start, end, content, sha256(content),
				Identifiers.searchText(filePath, symbol, content), estimateTokens(content));
	}

	/** Javadoc is attached as a comment, outside the node's own range; include it in the chunk. */
	private static int startLine(Node node) {
		int nodeStart = node.getRange().orElseThrow().begin.line;
		return node.getComment()
			.flatMap(Node::getRange)
			.map(r -> Math.min(r.begin.line, nodeStart))
			.orElse(nodeStart);
	}

	private static int endLine(Node node) {
		return node.getRange().orElseThrow().end.line;
	}

	private static boolean isLocal(TypeDeclaration<?> type) {
		return type.getParentNode()
			.map(p -> p instanceof LocalClassDeclarationStmt || p instanceof LocalRecordDeclarationStmt)
			.orElse(false);
	}

	/** {@code Outer.Inner} for nested types. */
	private static String nestedName(TypeDeclaration<?> type) {
		StringBuilder name = new StringBuilder(type.getNameAsString());
		Optional<Node> parent = type.getParentNode();
		while (parent.isPresent() && parent.get() instanceof TypeDeclaration<?> outer) {
			name.insert(0, outer.getNameAsString() + ".");
			parent = outer.getParentNode();
		}
		return name.toString();
	}

	private static String slice(String[] lines, int start, int end) {
		StringBuilder out = new StringBuilder();
		for (int i = start; i <= end && i <= lines.length; i++) {
			out.append(lines[i - 1]);
			if (i < end) {
				out.append('\n');
			}
		}
		return out.toString();
	}

	private static int countLinesOfCode(String[] lines) {
		int loc = 0;
		for (String line : lines) {
			if (!line.isBlank()) {
				loc++;
			}
		}
		return loc;
	}

	static int estimateTokens(String text) {
		return (text.length() + 3) / 4;
	}

	private static String sha256(String content) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
