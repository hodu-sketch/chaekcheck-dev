package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaekcheck.picks.ListedBooks.ListedBook;

/** {@link TitleMatch}: titles, title and author, authors pass; topic sentences and short words do not (issue 2-1). */
class TitleMatchTests {

	static final List<ListedBook> BOOKS = List.of(book("채식주의자", "한강 지음"), book("토지", "박경리"),
			book("철학은 날씨를 바꾼다", "김이진"), book("사랑의 기술", "에리히 프롬"));

	@Test
	void aTitleOrAnAuthorIsClose() {
		assertThat(TitleMatch.close("채식주의자", BOOKS)).isTrue();
		assertThat(TitleMatch.close("철학은 날씨를  바꾼다!", BOOKS)).isTrue();
		assertThat(TitleMatch.close("철학은 날씨를", BOOKS)).isTrue();
		assertThat(TitleMatch.close("토지 박경리", BOOKS)).isTrue();
		assertThat(TitleMatch.close("한강", BOOKS)).isTrue();
	}

	@Test
	void aTopicSentenceOrAShortWordIsNot() {
		assertThat(TitleMatch.close("세네카와 쇼펜하우어의 철학", BOOKS)).isFalse();
		assertThat(TitleMatch.close("사랑", BOOKS)).isFalse();
		assertThat(TitleMatch.close("토지 추천", BOOKS)).isFalse();
		assertThat(TitleMatch.close("토", BOOKS)).isFalse();
		assertThat(TitleMatch.close("", BOOKS)).isFalse();
	}

	private static ListedBook book(String title, String author) {
		return new ListedBook(1, title, author, null, new long[] { 101 }, List.of("kaist"));
	}

}
