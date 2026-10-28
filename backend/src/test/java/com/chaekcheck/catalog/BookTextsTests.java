package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.BookProfiles.Profile;
import com.chaekcheck.catalog.BookTexts.Tag;
import com.chaekcheck.catalog.BookTextsTransfer.BookProfile;
import com.chaekcheck.catalog.BookTextsTransfer.Cover;
import com.chaekcheck.catalog.BookTextsTransfer.DictionaryRow;
import com.chaekcheck.catalog.BookTextsTransfer.Summary;

/**
 * {@link BookTexts}, {@link CoverUrls}, {@link BookProfiles} and the summary, cover, tag and profile writes of
 * {@link BookTextsTransfer} (cycle 26 step 3). The list row covers follow ADR-0164 (AC-9): the edition's own cover,
 * else the latest edition of the same work with a cover, else none (the list's own picture).
 */
@SpringBootTest(properties = "chaekcheck.covers.base-url=https://test.cloudfront.net/")
@Transactional
class BookTextsTests {

	private static final LocalDate DAY = LocalDate.of(2026, 10, 28);

	private static final String KEY_A = "a".repeat(40);

	private static final String KEY_B = "b".repeat(40);

	private static final String KEY_C = "c".repeat(40);

	@Autowired
	BookTexts texts;

	@Autowired
	CoverUrls coverUrls;

	@Autowired
	BookProfiles profiles;

	@Autowired
	BookTextsTransfer transfer;

	@Autowired
	JdbcClient jdbc;

	@Test
	void summariesAndCoversByBook() {
		transfer.upsertSummaries(List.of(new Summary(11, "처음 요약이에요.", DAY), new Summary(12, "가".repeat(300), DAY)));
		transfer.upsertSummaries(List.of(new Summary(11, "고친 요약이에요.", DAY)));
		transfer.upsertCovers(List.of(new Cover(11, KEY_A)));

		assertThat(texts.summaries(Arrays.asList(11L, 12L, 13L, null, 11L)))
			.isEqualTo(Map.of(11L, "고친 요약이에요.", 12L, "가".repeat(300)));
		assertThat(texts.coverUrls(List.of(11L, 12L)))
			.isEqualTo(Map.of(11L, "https://test.cloudfront.net/covers/" + KEY_A + ".jpg"));
		assertThat(texts.summaries(List.of())).isEmpty();
	}

	@Test
	void coverAddresses() {
		assertThat(coverUrls.of(KEY_B)).isEqualTo("https://test.cloudfront.net/covers/" + KEY_B + ".jpg");
		assertThat(coverUrls.of("ABC")).isNull();
		assertThat(coverUrls.of(KEY_B.toUpperCase())).isNull();
		assertThat(coverUrls.of(null)).isNull();
	}

	@Test
	void badRowsAreRefusedBeforeTheDatabase() {
		assertThatThrownBy(() -> new Summary(11, "", DAY)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Summary(11, "가".repeat(301), DAY)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Summary(0, "요약", DAY)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Cover(11, "abc")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void tagsOnlyFromActiveCodesAndUnknownCodesSkipped() {
		transfer.replaceTagDictionary(List.of(new DictionaryRow("m01", "mood", "잔잔한", null),
				new DictionaryRow("s01", "subject", "소설", "FB"), new DictionaryRow("s02", "subject", "역사", null)));

		assertThat(transfer.replaceTags(11, List.of("s01", "m01", "s02", "nope"), DAY)).isEqualTo(1);
		assertThat(transfer.replaceTagDictionary(List.of(new DictionaryRow("m01", "mood", "잔잔한", null),
				new DictionaryRow("s01", "subject", "소설", "FB")))).isEqualTo(1);

		assertThat(texts.tags(List.of(11L, 12L)))
			.isEqualTo(Map.of(11L, List.of(new Tag("m01", "mood", "잔잔한"), new Tag("s01", "subject", "소설"))));
		assertThat(transfer.replaceTags(11, List.of(), DAY)).isZero();
		assertThat(texts.tags(List.of(11L))).isEmpty();
	}

	@Test
	void cardTagsAreOneMoodThenUpToTwoSubjects() {
		List<Tag> tags = List.of(new Tag("s02", "subject", "역사"), new Tag("a01", "audience", "청소년"),
				new Tag("m02", "mood", "밝은"), new Tag("s01", "subject", "소설"), new Tag("s03", "subject", "과학"),
				new Tag("m01", "mood", "잔잔한"));

		assertThat(BookTexts.forCard(tags)).extracting(Tag::code).containsExactly("m01", "s01", "s02");
		assertThat(BookTexts.forCard(tags.subList(0, 2))).extracting(Tag::code).containsExactly("s02");
	}

	@Test
	void listRowCoversTakeTheEditionThenTheLatestOtherEditionOfTheWork() {
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, title_norm, main_title_norm, published_on, content_type, library_ids)
				VALUES (940001, '돈의 속성', 'x', '돈의속성', DATE '2020-06-15', 'EBOOK', '{1,2}'),
				  (940002, '돈의 속성(개정)', 'x', '돈의속성', DATE '2023-01-01', 'EBOOK', '{1}'),
				  (940003, '돈의 속성(특별판)', 'x', '돈의속성', DATE '2023-01-01', 'EBOOK', '{1,2,3}'),
				  (940004, '돈의 속성(이북)', 'x', '돈의속성', DATE '2024-05-01', 'EBOOK', '{1}'),
				  (940005, '돈의 속성', 'x', '돈의속성', DATE '2025-01-01', 'EBOOK', '{1}'),
				  (940006, '고래', 'x', '고래', DATE '2004-12-01', 'EBOOK', '{1}')""").update();
		jdbc.sql("""
				INSERT INTO catalog.book_contributor (book_id, name, name_norm, name_exact, role, position)
				VALUES (940001, '김승호', 'x', '김승호', 'AUTHOR', 0), (940002, '김승호', 'x', '김승호', 'UNKNOWN', 0),
				  (940003, '김승호', 'x', '김승호', 'AUTHOR', 0), (940004, '옮긴이', 'x', '김승호', 'TRANSLATOR', 0),
				  (940005, '다른 사람', 'x', '다른사람', 'AUTHOR', 0), (940006, '천명관', 'x', '천명관', 'AUTHOR', 0)""")
			.update();
		// 940002 and 940003 are the latest editions of 940001's work with a cover; 940003 is held by more libraries.
		// 940004 (newer) shares the name only as a translator, 940005 (newer) has another author: not the same work.
		transfer.upsertCovers(List.of(new Cover(940002, KEY_A), new Cover(940003, KEY_B), new Cover(940004, KEY_C),
				new Cover(940005, KEY_C), new Cover(940006, KEY_A)));

		Map<Long, String> found = texts.listCoverUrls(List.of(940001L, 940002L, 940006L, 949999L));

		assertThat(found).containsOnlyKeys(940001L, 940002L, 940006L);
		assertThat(found.get(940001L)).endsWith(KEY_B + ".jpg");
		assertThat(found.get(940002L)).endsWith(KEY_A + ".jpg");
		jdbc.sql("DELETE FROM catalog.book_cover WHERE book_id IN (940002, 940003)").update();
		assertThat(texts.listCoverUrls(List.of(940001L))).isEmpty();
	}

	@Test
	void profilesAreReplacedAsAWholeAndReadBack() {
		Profile novel = new Profile("문학", "한국소설", true, List.of("성장", "가족"), "한국", "성인", "보통", List.of("장편"));
		transfer.replaceProfiles(List.of(new BookProfile(21, novel),
				new BookProfile(22, new Profile(null, null, null, List.of(), null, null, null, List.of()))));
		transfer.replaceProfiles(List.of(new BookProfile(21, novel), new BookProfile(23, novel)));

		Map<Long, Profile> all = profiles.all();

		assertThat(all).containsOnlyKeys(21L, 23L);
		assertThat(all.get(21L)).isEqualTo(novel);
		assertThat(all.get(21L).genres()).isSameAs(all.get(23L).genres());
		assertThat(profiles.version()).isEqualTo("2");
		assertThatThrownBy(() -> transfer.replaceProfiles(List.of())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> transfer.replaceProfiles(List.of(new BookProfile(21, novel), new BookProfile(21, novel))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(profiles.all()).hasSize(2);
	}

}
