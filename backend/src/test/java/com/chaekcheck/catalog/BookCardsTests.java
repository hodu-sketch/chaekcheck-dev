package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.BookCards.BookCard;
import com.chaekcheck.catalog.BookCards.HoldingKey;

/** Card fields, holding keys and work keys for the for-you tab (cycle 23 step 2, decision #6). */
@SpringBootTest
@Transactional
class BookCardsTests {

	@Autowired
	BookCards bookCards;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void books() {
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, author_display, publisher, title_norm, main_title_norm, published_on,
				  content_type, library_ids)
				VALUES (920001, '돈의 속성', '김승호', '스노우폭스북스', 'x', '돈의속성', DATE '2020-06-15', 'EBOOK', '{1,2}'),
				  (920002, '돈의 속성(100쇄 기념 에디션)', '김승호', '스노우폭스북스', 'x', '돈의속성', DATE '2020-12-01',
				   'EBOOK', '{1,2,3}'),
				  (920003, '고래', '천명관', '문학동네', 'x', '고래', DATE '2004-12-01', 'EBOOK', '{4}'),
				  (920004, '고래', '천명관', '문학동네', 'x', '고래', DATE '2014-02-01', 'EBOOK', '{5}'),
				  (920005, '고래', '다른 저자', '어디', 'x', '고래', NULL, 'EBOOK', '{6,7,8}'),
				  (920006, '책', NULL, NULL, 'x', '책', NULL, 'EBOOK', '{}'),
				  (920011, '세 판', '가', NULL, 'x', '세판', NULL, 'EBOOK', '{1}'),
				  (920012, '세 판', '가, 나', NULL, 'x', '세판', NULL, 'EBOOK', '{1,2}'),
				  (920013, '세 판', '나', NULL, 'x', '세판', NULL, 'EBOOK', '{1,2}')""").update();
		jdbc.sql("""
				INSERT INTO catalog.book_contributor (book_id, name, name_norm, name_exact, role, position)
				VALUES (920001, '김승호', 'x', '김승호', 'AUTHOR', 0),
				  (920002, '김승호', 'x', '김승호', 'UNKNOWN', 0),
				  (920003, '천명관', 'x', '천명관', 'AUTHOR', 0),
				  (920004, '옮긴이', 'x', '옮긴이', 'TRANSLATOR', 0),
				  (920004, '천명관', 'x', '천명관', 'AUTHOR', 1),
				  (920005, '다른 저자', 'x', '다른저자', 'AUTHOR', 0),
				  (920011, '가', 'x', '가', 'AUTHOR', 0),
				  (920012, '가', 'x', '가', 'AUTHOR', 0),
				  (920012, '나', 'x', '나', 'AUTHOR', 1),
				  (920013, '나', 'x', '나', 'AUTHOR', 0)""").update();
		jdbc.sql("""
				INSERT INTO catalog.holding (book_id, library_id, platform, item_key, detail_url, content_type, first_seen_at)
				VALUES (920001, 263, 'YES24', '4808954617383', 'https://e.example/a', 'EBOOK', now()),
				  (920003, 31, 'KYOBO', '4801156', 'https://k.example/b', 'EBOOK', now())""").update();
	}

	@Test
	void cardsCarryAuthorPublisherYearAndAuthorKey() {
		List<BookCard> cards = bookCards.cards(List.of(920004L, 920006L, 999999L, 920004L));

		assertThat(cards).extracting(BookCard::bookId).containsExactlyInAnyOrder(920004L, 920006L);
		BookCard whale = cards.stream().filter(card -> card.bookId() == 920004L).findFirst().orElseThrow();
		assertThat(whale.author()).isEqualTo("천명관");
		assertThat(whale.publisher()).isEqualTo("문학동네");
		assertThat(whale.publishedYear()).isEqualTo(2014);
		// the translator comes first by position but is not an author
		assertThat(whale.authorKey()).isEqualTo("천명관");
		assertThat(whale.libraryIds()).containsExactly(5);
		BookCard bare = cards.stream().filter(card -> card.bookId() == 920006L).findFirst().orElseThrow();
		assertThat(bare.publishedYear()).isNull();
		assertThat(bare.authorKey()).isNull();
		assertThat(bookCards.cards(List.of())).isEmpty();
	}

	@Test
	void holdingKeysFindOurBooks() {
		Map<HoldingKey, Long> found = bookCards.byHoldingKeys(List.of(new HoldingKey(263, "4808954617383"),
				new HoldingKey(31, "4801156"), new HoldingKey(263, "4801156"), new HoldingKey(1, "nothing")));

		assertThat(found).containsOnly(Map.entry(new HoldingKey(263, "4808954617383"), 920001L),
				Map.entry(new HoldingKey(31, "4801156"), 920003L));
		assertThat(bookCards.byHoldingKeys(List.of())).isEmpty();
	}

	@Test
	void editionsOfOneWorkShareTheMostHeldEdition() {
		Map<Long, Long> keys = bookCards.workKeys(List.of(920001L, 920002L, 920003L, 920004L, 920005L, 920006L, 999L));

		// 「돈의 속성」 and its 100th-printing edition; 「고래」 2004 and 2014; another author's 「고래」 alone
		assertThat(keys).containsOnly(Map.entry(920001L, 920002L), Map.entry(920002L, 920002L),
				Map.entry(920003L, 920003L), Map.entry(920004L, 920003L), Map.entry(920005L, 920005L),
				Map.entry(920006L, 920006L));
	}

	@Test
	void aSharedNameChainJoinsEveryEdition() {
		// 920011 and 920013 share no name but both share one with 920012; 920012 and 920013 tie, the smaller id wins
		assertThat(bookCards.workKeys(List.of(920011L, 920012L, 920013L))).containsOnly(Map.entry(920011L, 920012L),
				Map.entry(920012L, 920012L), Map.entry(920013L, 920012L));
	}

}
