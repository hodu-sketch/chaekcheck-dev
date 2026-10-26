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

	/**
	 * Cycle 14 (ADR-0089): picks uses catalog and library only. Cycle 21 (ADR-0123): collection uses picks
	 * (CuratedListTransfer); cycle 24 (ADR-0150): aisearch uses picks (ListedBooks); cycle 23 (ADR-0149): foryou too
	 * (listed books, card tags). No other module uses picks, and
	 * picks knows none of them.
	 */
	@Test
	void picksDependsOnCatalogAndLibraryOnly() {
		assertThat(dependenciesOf("picks")).containsExactlyInAnyOrder("catalog", "library");
		assertThat(modules.getModuleByName("popular")).isEmpty();
		modules.stream()
			.map(module -> module.getIdentifier().toString())
			.filter(name -> !List.of("picks", "collection", "aisearch", "foryou").contains(name))
			.forEach(name -> assertThat(dependenciesOf(name)).as(name).doesNotContain("picks"));
		assertThat(dependenciesOf("picks")).doesNotContain("collection", "aisearch", "foryou");
	}

	/**
	 * Cycle 24 (ADR-0150, ADR-0151): aisearch may use picks, library and usagelog only (and foryou's BookNeighbours,
	 * user 2026-10-25, and catalog's BookCards for the candidates' publish year, user 2026-10-26); only collection uses
	 * aisearch
	 * (AiSearchBookTexts for the book-texts mode), and aisearch does not know collection (no cycle).
	 */
	@Test
	void aisearchUsesPicksLibraryAndUsagelogOnly() {
		assertThat(dependenciesOf("aisearch")).isSubsetOf("picks", "library", "usagelog", "foryou", "catalog");
		modules.stream()
			.map(module -> module.getIdentifier().toString())
			.filter(name -> !List.of("aisearch", "collection").contains(name))
			.forEach(name -> assertThat(dependenciesOf(name)).as(name).doesNotContain("aisearch"));
		assertThat(dependenciesOf("aisearch")).doesNotContain("collection");
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
	 * collection uses catalog and library, since cycle 21 picks for the recommended lists (ADR-0123) and since cycle 24
	 * aisearch for the book-texts mode (ADR-0151) and since cycle 23 step 3-3 foryou for the neighbours mode and the
	 * for-you kinds of book-texts (ADR-0149); foryou does not know collection.
	 */
	@Test
	void collectionUsesLibraryAndLibraryNotCollection() {
		assertThat(dependenciesOf("collection")).containsExactlyInAnyOrder("catalog", "library", "picks", "aisearch",
				"foryou");
		assertThat(dependenciesOf("library")).doesNotContain("collection");
		assertThat(dependenciesOf("foryou")).doesNotContain("collection");
	}

	/**
	 * Cycle 15 (ADR-0097, ADR-0102): usagelog uses library and auth only; catalog and availability call usagelog, so
	 * the search modules still know nothing of auth (checked above), and usagelog uses neither of them (no cycle).
	 */
	@Test
	void usagelogUsesLibraryAndAuthOnly() {
		assertThat(dependenciesOf("usagelog")).containsExactlyInAnyOrder("library", "auth");
		assertThat(dependenciesOf("catalog")).contains("usagelog");
		assertThat(dependenciesOf("availability")).contains("usagelog");
		assertThat(dependenciesOf("auth")).doesNotContain("usagelog");
		assertThat(dependenciesOf("library")).doesNotContain("usagelog");
	}

	/**
	 * Cycle 23 (ADR-0149): foryou uses catalog, library, picks and availability only, and none of them knows foryou.
	 * picks is allowed but used only once cycle 24's ListedBooks is on main.
	 */
	@Test
	void foryouUsesItsFourModulesAndNoneUsesIt() {
		assertThat(List.of("catalog", "library", "picks", "availability")).containsAll(dependenciesOf("foryou"));
		for (String used : List.of("catalog", "library", "picks", "availability")) {
			assertThat(dependenciesOf(used)).as(used).doesNotContain("foryou");
		}
	}

	private List<String> dependenciesOf(String module) {
		ApplicationModule applicationModule = modules.getModuleByName(module).orElseThrow();
		return applicationModule.getDirectDependencies(modules)
			.uniqueModules()
			.map(dependency -> dependency.getIdentifier().toString())
			.toList();
	}

}
