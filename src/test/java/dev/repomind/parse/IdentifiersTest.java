package dev.repomind.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdentifiersTest {

	@Test
	void splitsCamelCaseAcronymsAndSnakeCase() {
		assertThat(Identifiers.split("findOwnerById")).containsExactly("find", "owner", "by", "id");
		assertThat(Identifiers.split("parseHTTPResponse")).containsExactly("parse", "http", "response");
		assertThat(Identifiers.split("MAX_RETRY_COUNT")).containsExactly("max", "retry", "count");
		assertThat(Identifiers.split("x")).isEmpty();
	}

	@Test
	void searchTextKeepsWholeIdentifiersAndDropsKeywords() {
		String text = Identifiers.searchText("public Owner findOwnerById(int id) { return repo.get(id); }");

		assertThat(text.split(" ")).contains("owner", "findownerbyid", "find", "by", "id", "repo", "get")
			.doesNotContain("public", "int", "return");
	}

}
