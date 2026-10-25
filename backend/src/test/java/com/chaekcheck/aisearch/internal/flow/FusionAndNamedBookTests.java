package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaekcheck.aisearch.internal.match.SearchBooks.SearchBook;

/** {@link Fusion} (the keyword + vector mix) and {@link NamedBook} (the book a "○○ 같은 책" question names). */
class FusionAndNamedBookTests {

	@Test
	void aBookInBothListsComesFirstAndTiesGoToTheKeywordList() {
		assertThat(Fusion.rrf(List.of(1L, 2L, 3L), List.of(9L, 3L), 60)).containsExactly(3L, 1L, 9L, 2L);
		assertThat(Fusion.rrf(List.of(), List.of(5L, 4L), 60)).containsExactly(5L, 4L);
		assertThat(Fusion.rrf(List.of(1L, 1L), List.of(), 60)).containsExactly(1L);
	}

	static final List<SearchBook> BOOKS = List.of(book(1, "아몬드"), book(2, "불편한 편의점"), book(3, "불편한 편의점 2"),
			book(4, "미움받을 용기"), book(5, "미움받을 용기(200만 부 기념 스페셜 에디션)"), book(6, "아이"), book(7, "편의점"),
			book(8, "앨리스와"));

	@Test
	void aTitleRightBeforeACueWordIsNamedWithAllItsEditions() {
		assertThat(NamedBook.find("아몬드 같은 성장 소설", BOOKS)).isEqualTo(new NamedBook("아몬드", List.of(1L)));
		assertThat(NamedBook.find("미움받을 용기 비슷한 책", BOOKS).editions()).containsExactly(4L, 5L);
		// the longest title ending right before the cue: 불편한 편의점, not 편의점
		assertThat(NamedBook.find("불편한 편의점처럼 따뜻한 소설", BOOKS).key()).isEqualTo("불편한편의점");
		assertThat(NamedBook.find("아몬드와 비슷한 소설", BOOKS).key()).isEqualTo("아몬드");
		assertThat(NamedBook.find("앨리스와 같은 동화", BOOKS).key()).isEqualTo("앨리스와");
	}

	@Test
	void aTitleWithoutACueOrAwayFromItIsNotNamed() {
		assertThat(NamedBook.find("아몬드 소설 추천", BOOKS)).isNull();
		assertThat(NamedBook.find("초등학교 고학년 아이와 같이 읽을 책", BOOKS)).isNull();
		assertThat(NamedBook.find("편의점에서 일하는 사람 같은 이야기", BOOKS)).isNull();
		assertThat(NamedBook.find("따뜻한 소설", BOOKS)).isNull();
	}

	private static SearchBook book(long id, String title) {
		return new SearchBook(id, title, "저자");
	}

}
