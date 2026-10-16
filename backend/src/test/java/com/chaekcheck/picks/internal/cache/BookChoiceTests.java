package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Cycle 21 handoff step 4-2 (ADR-0125, AC-6, AC-7): held counts, then the first volume, then the smaller id. */
class BookChoiceTests {

	/** 1-5 owned, 11-13 subscription; 99 is disabled (not in the map). */
	static final Map<Long, Boolean> KINDS = Map.of(1L, false, 2L, false, 3L, false, 4L, false, 5L, false, 11L, true,
			12L, true, 13L, true);

	/**
	 * The fixture the collector's Python tests read too (ADR-0124: the collector chooses the fixed editions by the same
	 * rule): scripts/crawl/testdata/book_choice_cases.json.
	 */
	@Test
	void theSameChoiceAsTheCollector() throws java.io.IOException {
		java.nio.file.Path file = java.nio.file.Path.of("..", "scripts", "crawl", "testdata", "book_choice_cases.json");
		tools.jackson.databind.JsonNode data = tools.jackson.databind.json.JsonMapper.builder()
			.build()
			.readTree(java.nio.file.Files.readString(file));
		Map<Long, Boolean> kinds = new java.util.HashMap<>();
		data.get("kinds").properties().forEach(entry -> kinds.put(Long.parseLong(entry.getKey()), entry.getValue().asBoolean()));
		assertThat(data.get("cases").size()).isGreaterThanOrEqualTo(6);
		for (tools.jackson.databind.JsonNode testCase : data.get("cases")) {
			List<BookChoice.Candidate> candidates = new java.util.ArrayList<>();
			for (tools.jackson.databind.JsonNode candidate : testCase.get("candidates")) {
				long[] libraries = new long[candidate.get("libraryIds").size()];
				for (int i = 0; i < libraries.length; i++) {
					libraries[i] = candidate.get("libraryIds").get(i).asLong();
				}
				candidates.add(new BookChoice.Candidate(candidate.get("id").asLong(), candidate.get("title").asString(),
						libraries));
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
		BookChoice.Candidate subscriptions = new BookChoice.Candidate(1, "구운몽", new long[] { 1, 2, 11, 12, 13 });
		BookChoice.Candidate owned = new BookChoice.Candidate(2, "구운몽", new long[] { 1, 2, 3, 4 });
		BookChoice.Candidate disabled = new BookChoice.Candidate(3, "구운몽", new long[] { 1, 99 });

		assertThat(BookChoice.choose(List.of(subscriptions, owned, disabled), KINDS)).contains(owned);
	}

	@Test
	void sameCountGivesTheFirstVolumeThenTheSmallerId() { // AC-7
		BookChoice.Candidate second = new BookChoice.Candidate(5, "토지. 2(1부 2권)", new long[] { 1, 2 });
		BookChoice.Candidate first = new BookChoice.Candidate(8, "토지. 1(1부 1권)", new long[] { 3, 4 });
		BookChoice.Candidate noVolume = new BookChoice.Candidate(7, "토지", new long[] { 1, 5 });

		assertThat(BookChoice.choose(List.of(second, first), KINDS)).contains(first);
		assertThat(BookChoice.choose(List.of(second, first, noVolume), KINDS)).contains(noVolume);
		assertThat(BookChoice.choose(List.of(), KINDS)).isEmpty();
	}

}
