package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A list shows each book once (user 2026-10-22: "각 추천도서 별로 중복인 거 다 없애주고"). */
class SameBookKeyTest {

	private static PicksCache.Book book(String id, String title, Long bookId) {
		return new PicksCache.Book(id, "arts", null, title, null, null, bookId, new long[0], 2026, null, null, null);
	}

	@Test
	void ourBookWhenMatchedElseTheTitlesLetters() {
		assertThat(PicksCache.sameBookKey(book("1", "아티스트 웨이", 9058L)))
			.isEqualTo(PicksCache.sameBookKey(book("2", "아티스트 웨이", 9058L)));
		assertThat(PicksCache.sameBookKey(book("1", "아티스트 웨이", 9058L)))
			.isNotEqualTo(PicksCache.sameBookKey(book("3", "아티스트 웨이", 1059L)));
		assertThat(PicksCache.sameBookKey(book("4", "세계 척학 전집!", null)))
			.isEqualTo(PicksCache.sameBookKey(book("5", "세계척학전집", null)));
	}

}
