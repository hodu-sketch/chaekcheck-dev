package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chaekcheck.aisearch.internal.match.SearchBooks.SearchBook;

class TailCheckTests {

	private static final Map<Long, SearchBook> KNOWN = Map.of(10L, new SearchBook(10, "사진 잘 찍는법", "김"), 11L,
			new SearchBook(11, "당신은 어떤 카메라를 사용 하시나요?", "정"), 12L, new SearchBook(12, "숲속 사진관에 온 편지", "이"));

	/** Candidates 1, 2, 3 = books 10, 11, 12; books 10 and 11 shown. */
	private static final List<Long> IDS = List.of(10L, 11L, 12L);

	private static final List<Long> SHOWN = List.of(10L, 11L);

	@Test
	void titlesOfShownCardsGoOutAsTheyAre() {
		String summary = "처음 배울 때: 『당신은 어떤 카메라를 사용 하시나요?』\n여행지에서: 『사진 잘 찍는법』";
		assertThat(TailCheck.clean(summary, IDS, SHOWN, KNOWN)).isEqualTo(summary);
		// a title cut short still matches its card
		assertThat(TailCheck.clean("여행지에서: 『사진 잘 찍는』", IDS, SHOWN, KNOWN)).isNotNull();
	}

	@Test
	void numbersOfShownCardsBecomeTheirTitles() { // production v1.21.0 「1, 16, 18번을 먼저 보시고」
		assertThat(TailCheck.clean("처음이라면 1, 2번을 먼저 보세요.", IDS, SHOWN, KNOWN))
			.isEqualTo("처음이라면 『사진 잘 찍는법』, 『당신은 어떤 카메라를 사용 하시나요?』을 먼저 보세요.");
		assertThat(TailCheck.clean("2번이 좋아요", IDS, SHOWN, KNOWN)).isEqualTo("『당신은 어떤 카메라를 사용 하시나요?』이 좋아요");
		assertThat(TailCheck.clean("두 번째로는 1분이면 돼요", IDS, SHOWN, KNOWN)).isEqualTo("두 번째로는 1분이면 돼요");
	}

	@Test
	void aBookNotShownLeavesTheSummaryOut() { // production v1.21.1 『숲속 사진관에 온 편지』
		assertThat(TailCheck.clean("쉬어 갈 때: 『숲속 사진관에 온 편지』", IDS, SHOWN, KNOWN)).isNull();
		assertThat(TailCheck.clean("쉬어 갈 때: 3번", IDS, SHOWN, KNOWN)).isNull();
		assertThat(TailCheck.clean("쉬어 갈 때: 30번", IDS, SHOWN, KNOWN)).isNull();
	}

}
