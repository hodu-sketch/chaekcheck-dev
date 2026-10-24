package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.picks.internal.cache.PicksCache;

/**
 * {@link ListedBooks} against db-test (cycle 24 handoff step 3): the server's copy and the import's own read give the
 * same books, shown names and authors (our book's {@code author_display}), one row per book over the lists, rows
 * without our book left out. Everything is written in the test transaction.
 */
@SpringBootTest
@Transactional
class ListedBooksTests {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PicksCache cache;

	@Autowired
	ListedBooks listed;

	@BeforeEach
	void stored() {
		PicksTestData data = new PicksTestData(jdbc);
		data.run();
		data.ourBook(910_001, "9791124591055", "코스모스 (개정판)", "칼 세이건", 1, 2);
		data.ourBook(910_002, null, "토지. 1(1부 1권)", "박경리", 3);
		jdbc.sql("UPDATE catalog.book SET author_display = :author WHERE id = :id")
			.param("author", "칼 세이건 지음")
			.param("id", 910_001)
			.update();
		jdbc.sql("UPDATE catalog.book SET author_display = :author WHERE id = :id")
			.param("author", "박경리")
			.param("id", 910_002)
			.update();
		// librarian: matched by ISBN when the cache is built
		data.pick("202609000001", "11", "문학", "2026-10", null, "코스모스", "칼 세이건", "9791124591055");
		// Lee Dong-jin: the import stored the matched book; one row without a match
		jdbc.sql("""
				INSERT INTO picks.list_field (list_key, code, name, position) VALUES ('leedongjin', 'novel', '소설', 99)
				ON CONFLICT (list_key, code) DO NOTHING""").update();
		insertLeedongjin(1, "토지", 910_002L);
		insertLeedongjin(2, "코스모스", 910_001L);
		insertLeedongjin(3, "우리 책에 없는 책", null);
		cache.refresh();
	}

	@Test
	void theServerCopyAndTheImportReadGiveTheSameBooks() {
		List<ListedBook> fromCache = ours(listed.current().books());
		List<ListedBook> fromDb = ours(listed.loadFromDb().books());

		assertThat(fromCache).extracting(ListedBook::bookId, ListedBook::title, ListedBook::author, ListedBook::listKeys)
			.containsExactly(tuple(910_001L, "코스모스", "칼 세이건 지음", List.of("librarian", "leedongjin")),
					tuple(910_002L, "토지", "박경리", List.of("leedongjin")));
		assertThat(fromDb).extracting(ListedBook::bookId, ListedBook::title, ListedBook::author, ListedBook::listKeys)
			.containsExactlyElementsOf(fromCache.stream()
				.map(book -> tuple(book.bookId(), book.title(), book.author(), book.listKeys()))
				.toList());
	}

	@Test
	void rowsWithoutOurBookAreLeftOut() {
		assertThat(listed.loadFromDb().books()).extracting(ListedBook::title).doesNotContain("우리 책에 없는 책");
	}

	private void insertLeedongjin(int position, String title, Long bookId) {
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, recommended_month, video_id, book_id)
				VALUES ('leedongjin', 'novel', :position, :title, DATE '2026-10-01', 'vid' || :position, :bookId)""")
			.param("position", position)
			.param("title", title)
			.param("bookId", bookId)
			.update();
	}

	/** Only this test's books: db-test may hold other rows. */
	private static List<ListedBook> ours(List<ListedBook> books) {
		return books.stream().filter(book -> book.bookId() >= 910_001 && book.bookId() <= 910_002).toList();
	}

}
