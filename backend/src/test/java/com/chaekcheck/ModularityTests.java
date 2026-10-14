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
	 * {@code verify()} already fails on a dependency outside mylibrary's {@code allowedDependencies}. Cycle 16
	 * (ADR-0099): auth reads the sign-up document versions from sitecontent, its only dependency.
	 */
	@Test
	void dependencyDirections() {
		assertThat(dependenciesOf("mylibrary")).containsExactlyInAnyOrder("auth", "library", "region");
		assertThat(dependenciesOf("auth")).containsExactly("sitecontent");
		for (String search : List.of("catalog", "availability", "library", "region")) {
			assertThat(dependenciesOf(search)).as(search).doesNotContain("auth", "mylibrary");
		}
	}

	/** Cycle 14 (ADR-0089): picks uses catalog and library only, and no module uses picks. */
	@Test
	void picksDependsOnCatalogAndLibraryOnly() {
		assertThat(dependenciesOf("picks")).containsExactlyInAnyOrder("catalog", "library");
		assertThat(modules.getModuleByName("popular")).isEmpty();
		modules.stream()
			.map(module -> module.getIdentifier().toString())
			.filter(name -> !name.equals("picks"))
			.forEach(name -> assertThat(dependenciesOf(name)).as(name).doesNotContain("picks"));
	}

	/** Cycle 16 (ADR-0098, ADR-0099): sitecontent depends on no module, and only auth depends on it. */
	@Test
	void sitecontentDependsOnNothingAndOnlyAuthUsesIt() {
		assertThat(dependenciesOf("sitecontent")).isEmpty();
		modules.stream()
			.map(module -> module.getIdentifier().toString())
			.filter(name -> !name.equals("auth"))
			.forEach(name -> assertThat(dependenciesOf(name)).as(name).doesNotContain("sitecontent"));
	}

	/**
	 * Cycle 13 (ADR-0083, ADR-0093): collection adds libraries through library's LibraryRegistry, never the other way;
	 * collection still uses only catalog and library.
	 */
	@Test
	void collectionUsesLibraryAndLibraryNotCollection() {
		assertThat(dependenciesOf("collection")).containsExactlyInAnyOrder("catalog", "library");
		assertThat(dependenciesOf("library")).doesNotContain("collection");
	}

	/**
	 * Cycle 15 (ADR-0097, ADR-0102): usagelog uses library and auth only; catalog and availability call usagelog, so
	 * the search modules still know nothing of auth (checked above), and usagelog uses neither of them (no cycle).
	 */
	@Test
	void usagelogUsesLibraryAndAuthOnly() {
		assertThat(dependenciesOf("usagelog")).containsExactlyInAnyOrder("library", "auth");
		assertThat(dependenciesOf("auth")).doesNotContain("usagelog");
		assertThat(dependenciesOf("library")).doesNotContain("usagelog");
	}

	private List<String> dependenciesOf(String module) {
		ApplicationModule applicationModule = modules.getModuleByName(module).orElseThrow();
		return applicationModule.getDirectDependencies(modules)
			.uniqueModules()
			.map(dependency -> dependency.getIdentifier().toString())
			.toList();
	}

}
