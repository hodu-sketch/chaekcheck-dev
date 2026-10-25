package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.MostHeldBooks.HeldBook;
import com.chaekcheck.picks.PicksTestData;

/**
 * {@link MostHeldBooks} against db-test (user 2026-10-25): most libraries first, then by id, with the author text and
 * the library ids; an empty author text counts as none.
 */
@SpringBootTest
@Transactional
class MostHeldBooksTests {

	@Autowired
	MostHeldBooks books;

	@Autowired
	JdbcClient jdbc;

	@Test
	void mostLibrariesFirstThenById() {
		PicksTestData data = new PicksTestData(jdbc);
		// far more libraries than any other test book, so these three are the top of db-test
		data.ourBook(940_003, null, "셋째", "가", libraries(9_000));
		data.ourBook(940_002, null, "둘째", null, libraries(9_500));
		data.ourBook(940_001, null, "첫째", null, libraries(9_500));
		jdbc.sql("UPDATE catalog.book SET author_display = CASE id WHEN 940003 THEN '가 지음' ELSE ' ' END "
				+ "WHERE id BETWEEN 940001 AND 940003").update();

		assertThat(books.top(3)).extracting(HeldBook::bookId, HeldBook::title, HeldBook::author)
			.containsExactly(tuple(940_001L, "첫째", null), tuple(940_002L, "둘째", null),
					tuple(940_003L, "셋째", "가 지음"));
		assertThat(books.top(1).getFirst().libraryIds()).hasSize(9_500).startsWith(1_000_001L);
		assertThat(books.top(0)).isEmpty();
	}

	private static long[] libraries(int count) {
		return LongStream.rangeClosed(1_000_001, 1_000_000 + count).toArray();
	}

}
