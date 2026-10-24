package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BookAuthors} against db-test (cycle 24): our books' {@code author_display} by id, books without one left out.
 */
@SpringBootTest
@Transactional
class BookAuthorsTests {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	BookAuthors authors;

	@Test
	void readsTheAuthorTextByIdAndLeavesOutBooksWithoutOne() {
		book(920_001, "칼 세이건 지음");
		book(920_002, null);
		book(920_003, "  ");

		assertThat(authors.byIds(Arrays.asList(920_001L, 920_002L, 920_003L, 920_009L, null, 920_001L)))
			.containsExactlyEntriesOf(java.util.Map.of(920_001L, "칼 세이건 지음"));
	}

	@Test
	void anEmptyInputGivesAnEmptyMap() {
		assertThat(authors.byIds(List.of())).isEmpty();
	}

	private void book(long id, String author) {
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, title_norm, main_title_norm, content_type, library_ids, author_display)
				VALUES (:id, '책', 'x', 'x', 'EBOOK', '{}', :author)""")
			.param("id", id)
			.param("author", author)
			.update();
	}

}
