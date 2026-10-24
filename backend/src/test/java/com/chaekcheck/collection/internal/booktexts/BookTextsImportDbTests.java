package com.chaekcheck.collection.internal.booktexts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.PicksTestData;

/**
 * The {@code book-texts} mode against db-test (cycle 24 handoff step 4 "넣기 통합 시험"): the listed books come from
 * picks' own database read with our book's shown title and author, the file's line joins its book, the line of a book
 * not listed is skipped and counted, and a second file replaces the first. Everything runs in the test transaction.
 */
@SpringBootTest
@Transactional
class BookTextsImportDbTests {

	@TempDir
	Path dir;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ListedBooks listedBooks;

	@Autowired
	AiSearchBookTexts bookTexts;

	@Test
	void putsInEveryListedBookAndTheFileLines() throws Exception {
		PicksTestData data = new PicksTestData(jdbc);
		data.run();
		data.ourBook(930_001, null, "토지. 1(1부 1권)", "박경리", 3);
		data.ourBook(930_002, null, "코스모스", "칼 세이건", 1);
		jdbc.sql("UPDATE catalog.book SET author_display = '박경리 지음' WHERE id = 930001").update();
		jdbc.sql("UPDATE catalog.book SET author_display = '칼 세이건' WHERE id = 930002").update();
		jdbc.sql("""
				INSERT INTO picks.list_field (list_key, code, name, position) VALUES ('leedongjin', 'novel', '소설', 99)
				ON CONFLICT (list_key, code) DO NOTHING""").update();
		leedongjin(1, "토지", 930_001L);
		leedongjin(2, "코스모스", 930_002L);
		BookTextsImport command = new BookTextsImport(listedBooks, bookTexts);

		BookTextsImport.Report first = command.run(file("""
				kind\tbook_id\tsource\tmade_on\ttext
				search_line\t930001\tintro\t2026-10-24\t한 집안의 오랜 역사를 그린 대하소설
				search_line\t939999\tintro\t2026-10-24\t목록에 없는 책
				"""));

		assertThat(first.result().linesNotListed()).isEqualTo(1);
		assertThat(rows()).containsExactly(tuple(930_001L, "토지", "박경리 지음", "한 집안의 오랜 역사를 그린 대하소설"),
				tuple(930_002L, "코스모스", "칼 세이건", null));

		command.run(file("""
				kind\tbook_id\tsource\tmade_on\ttext
				search_line\t930002\tbibliographic\t2026-10-25\t우주를 다룬 교양서
				"""));

		assertThat(rows()).containsExactly(tuple(930_001L, "토지", "박경리 지음", null),
				tuple(930_002L, "코스모스", "칼 세이건", "우주를 다룬 교양서"));
	}

	private List<Tuple> rows() {
		return jdbc.sql("""
				SELECT book_id, title, author, line FROM aisearch.search_book
				WHERE book_id BETWEEN 930001 AND 930002 ORDER BY book_id""")
			.query((rs, n) -> tuple(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
			.list();
	}

	private void leedongjin(int position, String title, Long bookId) {
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, recommended_month, video_id, book_id)
				VALUES ('leedongjin', 'novel', :position, :title, DATE '2026-10-01', 'vid' || :position, :bookId)""")
			.param("position", position)
			.param("title", title)
			.param("bookId", bookId)
			.update();
	}

	private Path file(String text) throws Exception {
		Path file = Files.createTempFile(dir, "book-texts", ".tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
