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

/** {@code byIsbn13} and {@code byTitleAndAuthor} for the librarian picks (plan cycle 14 handoff step 1, ADR-0090). */
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
				  (910003, '다', '다', '다', NULL, 'EBOOK', '{1}'),
				  (910011, '최소한의 양자역학', 'x', 'x', NULL, 'EBOOK', '{1,2}'),
				  (910012, '최소한의 양자역학 : 개정판', 'x', 'x', NULL, 'EBOOK', '{5}'),
				  (910013, '최소한의 양자역학', 'x', 'x', NULL, 'EBOOK', '{6}'),
				  (910014, '해', 'x', 'x', NULL, 'EBOOK', '{4}'),
				  (910015, '해와 달', 'x', 'x', NULL, 'EBOOK', '{4}')""").update();
		jdbc.sql("""
				INSERT INTO catalog.book_contributor (book_id, name, name_norm, name_exact, role, position)
				VALUES (910011, '프랑크 베르스트라테', 'x', '프랑크베르스트라테', 'AUTHOR', 0),
				  (910011, '최진영', 'x', '최진영', 'TRANSLATOR', 1),
				  (910012, '프랑크 베르스트라테', 'x', '프랑크베르스트라테', 'UNKNOWN', 0),
				  (910013, '다른 사람', 'x', '다른사람', 'AUTHOR', 0),
				  (910014, '홍길동', 'x', '홍길동', 'AUTHOR', 0),
				  (910014, '홍길동', 'x', '홍길동', 'ILLUSTRATOR', 0),
				  (910015, '홍길동', 'x', '홍길동', 'AUTHOR', 0)""").update();
	}

	@Test
	void sameTitleKeyAndFirstAuthorGivesEveryMatchingBook() {
		TitleAuthor query = new TitleAuthor("(아무도 모르지만 누구나 알아야 할) 최소한의 양자역학",
				"프랑크 베르스트라테,셀린 브뢰카에르트 지음 ;최진영 옮김");

		List<TitleAuthorBook> found = bookLookup.byTitleAndAuthor(List.of(query));

		// 910012 has another subtitle, 910013 another author
		assertThat(found).extracting(TitleAuthorBook::bookId).containsExactlyInAnyOrder(910011L, 910012L);
		assertThat(found).allSatisfy(book -> assertThat(book.query()).isSameAs(query));
		TitleAuthorBook first = found.stream().filter(book -> book.bookId() == 910011L).findFirst().orElseThrow();
		assertThat(first.libraryIds()).containsExactly(1, 2);
	}

	@Test
	void otherAuthorOrOtherTitleGivesNothing() {
		assertThat(bookLookup.byTitleAndAuthor(List.of(new TitleAuthor("최소한의 양자역학", "김아무개 지음")))).isEmpty();
		assertThat(bookLookup.byTitleAndAuthor(List.of(new TitleAuthor("최대한의 양자역학", "프랑크 베르스트라테 지음"))))
			.isEmpty();
	}

	@Test
	void roleWordsAndOneLetterTitles() {
		List<TitleAuthorBook> found = bookLookup.byTitleAndAuthor(List.of(new TitleAuthor("해", "홍길동 지음")));

		// 글그림 gives two contributor rows for one person: still one row
		assertThat(found).extracting(TitleAuthorBook::bookId).containsExactly(910014L);
	}

	@Test
	void emptyRepeatedAndUnusableQueries() {
		assertThat(bookLookup.byTitleAndAuthor(List.of())).isEmpty();
		assertThat(bookLookup.byTitleAndAuthor(java.util.Arrays.asList(null, new TitleAuthor("(세트)", "홍길동"),
				new TitleAuthor("해", null), new TitleAuthor("해", " "))))
			.isEmpty();
		TitleAuthor query = new TitleAuthor("해와 달 : 옛이야기", "홍길동");
		assertThat(bookLookup.byTitleAndAuthor(List.of(query, query))).extracting(TitleAuthorBook::bookId)
			.containsExactly(910015L);
		assertThat(bookLookup.byTitleAndAuthor(List.of(query))).extracting(TitleAuthorBook::bookId)
			.containsExactly(910015L);
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
