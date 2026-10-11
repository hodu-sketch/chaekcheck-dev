package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** {@code byIsbn13} for the popular books (plan cycle 10 handoff step 1, ADR-0074). */
@SpringBootTest
@Transactional
class BookLookupTests {

	@Autowired
	BookLookup bookLookup;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void books() {
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, title_norm, main_title_norm, isbn13, content_type, library_ids)
				VALUES (910001, '가', '가', '가', '9799990000011', 'EBOOK', '{3,1,7}'),
				  (910002, '나', '나', '나', '9799990000028', 'EBOOK', '{}'),
				  (910003, '다', '다', '다', NULL, 'EBOOK', '{1}')""").update();
	}

	@Test
	void matchingIsbnsGiveBookIdAndLibraryIds() {
		List<IsbnBook> found = bookLookup.byIsbn13(List.of("9799990000011", "9799990000028"));

		assertThat(found).extracting(IsbnBook::isbn13, IsbnBook::bookId)
			.containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("9799990000011", 910001L),
					org.assertj.core.groups.Tuple.tuple("9799990000028", 910002L));
		IsbnBook first = found.stream().filter(book -> book.bookId() == 910001L).findFirst().orElseThrow();
		assertThat(first.libraryIds()).containsExactly(3, 1, 7);
		IsbnBook empty = found.stream().filter(book -> book.bookId() == 910002L).findFirst().orElseThrow();
		assertThat(empty.libraryIds()).isEmpty();
	}

	@Test
	void unknownBadAndRepeatedIsbns() {
		assertThat(bookLookup.byIsbn13(List.of())).isEmpty();
		assertThat(bookLookup.byIsbn13(List.of("9799990000035"))).isEmpty();
		assertThat(bookLookup.byIsbn13(List.of("979-9990000011", "x", ""))).isEmpty();
		assertThat(bookLookup.byIsbn13(java.util.Arrays.asList("9799990000011", null, "9799990000011")))
			.extracting(IsbnBook::bookId)
			.containsExactly(910001L);
	}

	@Test
	void eightHundredFiftyIsbnsInOneQuery() {
		List<String> isbns = new ArrayList<>();
		for (int i = 0; i < 849; i++) {
			isbns.add("97999900%05d".formatted(10000 + i));
		}
		isbns.add("9799990000011");

		assertThat(bookLookup.byIsbn13(isbns)).extracting(IsbnBook::bookId).contains(910001L);
	}

}
