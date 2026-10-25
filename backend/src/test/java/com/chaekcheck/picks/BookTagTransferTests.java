package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.picks.BookTagTransfer.DictionaryRow;
import com.chaekcheck.picks.BookTagTransfer.Tag;

/** The tag dictionary, book tags and the card's choice (cycle 23 step 4, R9-5, AC-10). */
@SpringBootTest
@Transactional
class BookTagTransferTests {

	static final LocalDate DAY = LocalDate.of(2026, 10, 24);

	@Autowired
	BookTagTransfer transfer;

	@Autowired
	JdbcClient jdbc;

	@Test
	void dictionaryUpsertKeepsLeftOutCodesInactive() {
		transfer.upsertDictionary(List.of(new DictionaryRow("m01", "mood", "잔잔한", null),
				new DictionaryRow("s01", "subject", "소설", "FB")));
		transfer.replaceTags(11, List.of("m01"), DAY);

		int madeInactive = transfer.upsertDictionary(List.of(new DictionaryRow("s01", "subject", "장편소설", "FBA"),
				new DictionaryRow("s02", "subject", "역사", "NH")));

		assertThat(madeInactive).isEqualTo(1);
		assertThat(jdbc.sql("SELECT code || ':' || name || ':' || active FROM picks.tag_dictionary ORDER BY code")
			.query(String.class)
			.list()).containsExactly("m01:잔잔한:false", "s01:장편소설:true", "s02:역사:true");
		// the book keeps its tag; the card leaves inactive codes out when it reads them
		assertThat(tagsOf(11)).containsExactly("m01");
	}

	@Test
	void replacingTagsSkipsUnknownCodesAndReplacesTheWholeSet() {
		transfer.upsertDictionary(List.of(new DictionaryRow("m01", "mood", "잔잔한", null),
				new DictionaryRow("s01", "subject", "소설", null), new DictionaryRow("s02", "subject", "역사", null)));

		assertThat(transfer.replaceTags(11, List.of("m01", "s01", "zz9"), DAY)).isEqualTo(1);
		assertThat(tagsOf(11)).containsExactly("m01", "s01");
		assertThat(transfer.replaceTags(11, List.of("s02"), DAY)).isZero();
		assertThat(tagsOf(11)).containsExactly("s02");
		assertThat(transfer.replaceTags(11, List.of(), DAY)).isZero();
		assertThat(tagsOf(11)).isEmpty();
	}

	@Test
	void cardShowsOneMoodAndOneOrTwoSubjects() {
		Tag calm = new Tag("m02", "mood", "잔잔한");
		Tag warm = new Tag("m01", "mood", "따뜻한");
		Tag novel = new Tag("s03", "subject", "소설");
		Tag history = new Tag("s01", "subject", "역사");
		Tag family = new Tag("s02", "subject", "가족");
		Tag teen = new Tag("a01", "audience", "청소년");

		assertThat(BookTagTransfer.forCard(List.of(calm, novel, history, family, warm, teen))).containsExactly(warm,
				history, family);
		assertThat(BookTagTransfer.forCard(List.of(novel, teen))).containsExactly(novel);
		assertThat(BookTagTransfer.forCard(List.of(history, family, novel))).containsExactly(history, family);
		assertThat(BookTagTransfer.forCard(List.of(teen))).isEmpty();
	}

	private List<String> tagsOf(long bookId) {
		return jdbc.sql("SELECT tag_code FROM picks.book_tag WHERE book_id = ? ORDER BY tag_code")
			.param(bookId)
			.query(String.class)
			.list();
	}

}
