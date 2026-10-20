package dev.repomind.api;

import java.util.List;
import java.util.UUID;

import dev.repomind.repo.RepoDao;
import dev.repomind.search.CodeNavigator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClassController {

	private final CodeNavigator navigator;

	private final RepoDao repos;

	public ClassController(CodeNavigator navigator, RepoDao repos) {
		this.navigator = navigator;
		this.repos = repos;
	}

	/**
	 * Outline of a type: skeleton, members with locations, and code that refers to it. Several
	 * entries mean the simple name exists in more than one package.
	 */
	@GetMapping("/api/repos/{id}/classes/{name}")
	List<CodeNavigator.ClassOutline> outline(@PathVariable UUID id, @PathVariable String name) {
		var repo = repos.findById(id).orElseThrow(() -> new NotFoundException("No repository " + id));
		List<CodeNavigator.ClassOutline> outlines = navigator.outline(repo, name);
		if (outlines.isEmpty()) {
			throw new NotFoundException("No type named '" + name + "' in " + repo.fullName());
		}
		return outlines;
	}

}
