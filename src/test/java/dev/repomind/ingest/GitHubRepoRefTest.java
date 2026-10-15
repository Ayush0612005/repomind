package dev.repomind.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubRepoRefTest {

	@ParameterizedTest
	@ValueSource(strings = { "spring-projects/spring-petclinic", "github.com/spring-projects/spring-petclinic",
			"https://github.com/spring-projects/spring-petclinic", "https://github.com/spring-projects/spring-petclinic.git",
			"https://www.github.com/spring-projects/spring-petclinic/tree/main/src", " spring-projects/spring-petclinic/ " })
	void acceptsCommonForms(String input) {
		GitHubRepoRef ref = GitHubRepoRef.parse(input);

		assertThat(ref.owner()).isEqualTo("spring-projects");
		assertThat(ref.name()).isEqualTo("spring-petclinic");
		assertThat(ref.cloneUrl()).isEqualTo("https://github.com/spring-projects/spring-petclinic.git");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "petclinic", "https://gitlab.com/a/b", "file:///etc/passwd", "a/..", "-bad/name",
			"owner/name with space", "https://github.com/owner" })
	void rejectsNonGitHubInput(String input) {
		assertThatIllegalArgumentException().isThrownBy(() -> GitHubRepoRef.parse(input));
	}

}
