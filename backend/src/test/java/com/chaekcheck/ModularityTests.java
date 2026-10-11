package com.chaekcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	private final ApplicationModules modules = ApplicationModules.of(ChaekcheckApplication.class);

	@Test
	void verifiesModularStructure() {
		modules.verify();
	}

	/**
	 * Cycle 06 (ADR-0048, ADR-0037): only mylibrary uses auth, and the search modules know neither auth nor mylibrary.
	 * {@code verify()} already fails on a dependency outside mylibrary's {@code allowedDependencies}.
	 */
	@Test
	void dependencyDirections() {
		assertThat(dependenciesOf("mylibrary")).containsExactlyInAnyOrder("auth", "library", "region");
		assertThat(dependenciesOf("auth")).isEmpty();
		for (String search : List.of("catalog", "availability", "library", "region")) {
			assertThat(dependenciesOf(search)).as(search).doesNotContain("auth", "mylibrary");
		}
	}

	/** Cycle 10 (ADR-0073): popular uses catalog and library only, and no module uses popular. */
	@Test
	void popularDependsOnCatalogAndLibraryOnly() {
		assertThat(dependenciesOf("popular")).containsExactlyInAnyOrder("catalog", "library");
		modules.stream()
			.map(module -> module.getIdentifier().toString())
			.filter(name -> !name.equals("popular"))
			.forEach(name -> assertThat(dependenciesOf(name)).as(name).doesNotContain("popular"));
	}

	private List<String> dependenciesOf(String module) {
		ApplicationModule applicationModule = modules.getModuleByName(module).orElseThrow();
		return applicationModule.getDirectDependencies(modules)
			.uniqueModules()
			.map(dependency -> dependency.getIdentifier().toString())
			.toList();
	}

}
