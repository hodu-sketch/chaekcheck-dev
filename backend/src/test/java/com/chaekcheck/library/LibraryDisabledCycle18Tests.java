package com.chaekcheck.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Cycle 18 step A1 (ADR-0109, spec R1, AC-2) against db-test: {@code library/016-library-disabled-cycle18.yaml} turns
 * off the 22 libraries with no e-books and nothing else, and the public list leaves them out. Reads only; the seed is
 * what Liquibase left.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LibraryDisabledCycle18Tests {

	/** Turned off by changeset library-016-disabled-cycle18 (user decision 2026-10-14). */
	static final List<Long> CYCLE18 = List.of(44L, 112L, 168L, 261L, 269L, 305L, 318L, 323L, 378L, 425L, 455L, 514L,
			537L, 622L, 623L, 625L, 626L, 721L, 1045L, 1131L, 1620L, 2023L);

	/** Off before cycle 18: the Kyobo seed (59, 131) and library/010 (135, 2194, 2198). */
	static final List<Long> EARLIER = List.of(59L, 131L, 135L, 2194L, 2198L);

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void theChangesetTurnsOffExactlyTheTwentyTwo() {
		assertThat(jdbc.sql("SELECT exectype FROM public.databasechangelog WHERE id = 'library-016-disabled-cycle18'")
			.query(String.class)
			.single()).isEqualTo("EXECUTED");
		assertThat(jdbc.sql("SELECT count(*) FROM library.library WHERE id IN (:ids)")
			.param("ids", CYCLE18)
			.query(Long.class)
			.single()).isEqualTo(22);
		List<Long> disabled = jdbc.sql("SELECT id FROM library.library WHERE NOT enabled ORDER BY id")
			.query(Long.class)
			.list();
		assertThat(disabled).containsExactlyInAnyOrderElementsOf(
				Stream.concat(EARLIER.stream(), CYCLE18.stream()).toList());
	}

	@Test
	void theListLeavesThemOut() { // AC-2
		long enabled = jdbc.sql("SELECT count(*) FROM library.library WHERE enabled").query(Long.class).single();
		var body = assertThat(mvc.get().uri("/api/v1/libraries")).hasStatusOk().bodyJson();
		body.extractingPath("$.libraries").asArray().hasSize((int) enabled);
		List<Integer> ids = CYCLE18.stream().map(Long::intValue).toList();
		body.extractingPath("$.libraries[*].id").asArray().doesNotContainAnyElementsOf(ids);
	}

}
