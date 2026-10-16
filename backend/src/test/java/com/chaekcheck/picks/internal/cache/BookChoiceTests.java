package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cycle 21 handoff step 4-2 (ADR-0125, plan scope decision #7, AC-6, AC-7): held counts, a set held as its volume held
 * most and shown by its first volume, then the smaller id.
 */
class BookChoiceTests {

	/** 1-5 owned, 11-13 subscription; 99 is disabled (not in the map). */
	static final Map<Long, Boolean> KINDS = Map.of(1L, false, 2L, false, 3L, false, 4L, false, 5L, false, 11L, true,
			12L, true, 13L, true);

	/**
	 * The fixture the collector's Python tests read too (ADR-0124: the collector chooses the fixed editions by the same
	 * rule): scripts/crawl/testdata/book_choice_cases.json.
	 */
	@Test
	void theSameChoiceAsTheCollector() throws IOException {
		JsonNode data = JsonMapper.builder()
			.build()
			.readTree(Files.readString(Path.of("..", "scripts", "crawl", "testdata", "book_choice_cases.json")));
		Map<Long, Boolean> kinds = new HashMap<>();
		data.get("kinds").properties().forEach(entry -> kinds.put(Long.parseLong(entry.getKey()), entry.getValue().asBoolean()));
		assertThat(data.get("cases").size()).isGreaterThanOrEqualTo(8);
		for (JsonNode testCase : data.get("cases")) {
			List<BookChoice.Candidate> candidates = new ArrayList<>();
			for (JsonNode candidate : testCase.get("candidates")) {
				long[] libraries = new long[candidate.get("libraryIds").size()];
				for (int i = 0; i < libraries.length; i++) {
					libraries[i] = candidate.get("libraryIds").get(i).asLong();
				}
				candidates.add(new BookChoice.Candidate(candidate.get("id").asLong(), candidate.get("title").asString(),
						candidate.get("publisher").asString(), libraries));
			}
			assertThat(BookChoice.choose(candidates, kinds).orElseThrow().bookId()).as(testCase.get("name").asString())
				.isEqualTo(testCase.get("chosen").asLong());
		}
	}

	@Test
	void subscriptionLibrariesCountAsOne() {
		assertThat(BookChoice.held(new long[] { 11, 12, 13 }, KINDS)).isEqualTo(1);
		assertThat(BookChoice.held(new long[] { 1, 2, 11, 12, 13 }, KINDS)).isEqualTo(3);
		assertThat(BookChoice.held(new long[0], KINDS)).isZero();
	}

	@Test
	void disabledLibrariesCountForNothing() {
		assertThat(BookChoice.held(new long[] { 1, 99 }, KINDS)).isEqualTo(1);
		assertThat(BookChoice.held(new long[] { 99 }, KINDS)).isZero();
	}

	@Test
	void mostHeldWins() { // AC-6: two owned and three subscription (3) lose to four owned (4)
		BookChoice.Candidate subscriptions = candidate(1, "구운몽", "가", 1, 2, 11, 12, 13);
		BookChoice.Candidate owned = candidate(2, "구운몽", "나", 1, 2, 3, 4);
		BookChoice.Candidate disabled = candidate(3, "구운몽", "다", 1, 99);

		assertThat(BookChoice.choose(List.of(subscriptions, owned, disabled), KINDS)).contains(owned);
	}

	@Test
	void aSetIsHeldAsItsVolumeHeldMostAndShowsItsFirstVolume() { // AC-7
		BookChoice.Candidate second = candidate(5, "토지. 2(1부 2권)", "마로니에북스", 1, 2, 3);
		BookChoice.Candidate first = candidate(8, "토지. 1(1부 1권)", "마로니에북스", 1, 2);
		BookChoice.Candidate other = candidate(7, "토지 1", "다산책방", 4, 5);

		assertThat(BookChoice.choose(List.of(second, first, other), KINDS)).contains(first);
		assertThat(BookChoice.choose(List.of(), KINDS)).isEmpty();
	}

	@Test
	void aSetWithoutAFirstVolumeShowsItsSmallest() {
		BookChoice.Candidate third = candidate(5, "탁류 3", "지혜의숲", 1, 2);
		BookChoice.Candidate second = candidate(9, "탁류 2", "지혜의숲", 1);

		assertThat(BookChoice.choose(List.of(third, second), KINDS)).contains(second);
	}

	@Test
	void anotherPublishersVolumesAreAnotherSet() {
		BookChoice.Candidate a = candidate(5, "고향 (하)", "크리에이트플러스", 1, 2, 3);
		BookChoice.Candidate b = candidate(6, "고향 (상)", "북향", 1);

		assertThat(BookChoice.choose(List.of(a, b), KINDS)).contains(a);
	}

	private static BookChoice.Candidate candidate(long id, String title, String publisher, long... libraries) {
		return new BookChoice.Candidate(id, title, publisher, libraries);
	}

}
