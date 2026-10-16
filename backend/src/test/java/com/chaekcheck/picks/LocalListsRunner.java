package com.chaekcheck.picks;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.chaekcheck.catalog.BookLookup;

/**
 * Local development only (cycle 21 steps 8-3 and 9): reads the list files (scripts/crawl/lists), matches them to the
 * catalog of the database named by {@code CHAEKCHECK_LISTS_DB_URL} and puts them in there, as an in-place import
 * would after its swap; the counts go to the log. Liquibase runs on that database first. Never runs in the build:
 *
 * <pre>
 * CHAEKCHECK_LISTS_DB_URL=jdbc:postgresql://localhost:5502/chaekcheck ./gradlew test --tests '*LocalListsRunner' --rerun
 * </pre>
 *
 * Use it on a copy (the cycle's own database), never on the original one (handoff: no import files from 5432).
 */
@EnabledIfEnvironmentVariable(named = "CHAEKCHECK_LISTS_DB_URL", matches = "jdbc:postgresql://localhost:(?!5432/).+")
@SpringBootTest(properties = { "spring.datasource.url=${CHAEKCHECK_LISTS_DB_URL}", "chaekcheck.picks.fetch.enabled=false" })
class LocalListsRunner {

	@Autowired
	CuratedListTransfer transfer;

	@Autowired
	BookLookup lookup;

	@Test
	void putTheListsIn() {
		Path lists = Path.of("..", "scripts", "crawl", "lists");
		long start = System.nanoTime();
		CuratedListTransfer.ListPart part = transfer.read(lists, lookup);
		long readMs = (System.nanoTime() - start) / 1_000_000;
		start = System.nanoTime();
		CuratedListTransfer.Replaced replaced = transfer.replace(part.json());
		long replaceMs = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("LISTS counts %s, read %d ms, listPart %d chars, replace %s in %d ms%n", part.counts(), readMs,
				part.json().length(), replaced, replaceMs);
		part.warnings().forEach(warning -> System.out.println("LISTS warning " + warning));
	}

}
