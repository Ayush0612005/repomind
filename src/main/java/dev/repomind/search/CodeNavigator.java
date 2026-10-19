package dev.repomind.search;

import java.util.List;

import dev.repomind.repo.Repo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Structural lookups over a repository's active index generation (no model involved). */
@Service
public class CodeNavigator {

	/** A type with its skeleton, members and the places that mention it. */
	public record ClassOutline(String symbol, String kind, String location, String skeleton, List<Member> members,
			List<Usage> usages) {
	}

	public record Member(String symbol, String kind, String location) {
	}

	public record Usage(String symbol, String location) {
	}

	private record TypeRow(String filePath, String typeName, String symbol, String kind, int startLine, int endLine,
			String content) {

		String location() {
			return filePath + ":" + startLine + "-" + endLine;
		}

	}

	private static final int MAX_USAGES = 10;

	private final JdbcClient jdbc;

	private final HybridSearch search;

	public CodeNavigator(JdbcClient jdbc, HybridSearch search) {
		this.jdbc = jdbc;
		this.search = search;
	}

	/**
	 * Types named {@code className}: a simple name ({@code LoanService}), a nested name
	 * ({@code LoanExceptions.BookUnavailableException}) or a fully qualified one. Several results
	 * mean the simple name is ambiguous across packages.
	 */
	public List<ClassOutline> outline(Repo repo, String className) {
		if (repo.activeJobId() == null) {
			throw new NotIndexedException(repo);
		}
		String name = className.strip();
		List<TypeRow> types = jdbc.sql("""
				SELECT file_path, type_name, symbol, kind, start_line, end_line, content
				FROM code_chunks
				WHERE ingest_job_id = :job
				  AND kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION')
				  AND (symbol = :name OR type_name = :name OR type_name LIKE '%.' || :name)
				ORDER BY symbol
				LIMIT 10
				""")
			.param("job", repo.activeJobId())
			.param("name", name)
			.query((rs, row) -> new TypeRow(rs.getString("file_path"), rs.getString("type_name"),
					rs.getString("symbol"), rs.getString("kind"), rs.getInt("start_line"), rs.getInt("end_line"),
					rs.getString("content")))
			.list();
		return types.stream().map(type -> outline(repo, type)).toList();
	}

	private ClassOutline outline(Repo repo, TypeRow type) {
		List<Member> members = jdbc.sql("""
				SELECT symbol, kind, file_path, start_line, end_line
				FROM code_chunks
				WHERE ingest_job_id = :job AND file_path = :file AND type_name = :type
				  AND kind IN ('METHOD', 'CONSTRUCTOR')
				ORDER BY start_line, id
				""")
			.param("job", repo.activeJobId())
			.param("file", type.filePath())
			.param("type", type.typeName())
			.query((rs, row) -> new Member(rs.getString("symbol"), rs.getString("kind"),
					rs.getString("file_path") + ":" + rs.getInt("start_line") + "-" + rs.getInt("end_line")))
			.list();
		// identifier search for the simple name finds callers, fields and imports elsewhere
		String simpleName = type.typeName().substring(type.typeName().lastIndexOf('.') + 1);
		List<Usage> usages = search.search(repo, simpleName, MAX_USAGES * 2, HybridSearch.Mode.KEYWORD)
			.stream()
			.filter(hit -> !hit.filePath().equals(type.filePath()))
			.limit(MAX_USAGES)
			.map(hit -> new Usage(hit.symbol(), hit.citation()))
			.toList();
		return new ClassOutline(type.symbol(), type.kind(), type.location(), type.content(), members, usages);
	}

}
