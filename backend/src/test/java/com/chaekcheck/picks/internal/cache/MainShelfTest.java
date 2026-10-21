package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class MainShelfTest {

	private static PicksCache.Book book(String id, String field, int year, int held, boolean ours, boolean cover) {
		long[] libraries = new long[held];
		for (int i = 0; i < held; i++) {
			libraries[i] = i + 1;
		}
		return new PicksCache.Book(id, field, null, id, null, cover ? "/covers/" + id + ".jpg" : null,
				ours ? 1L : null, libraries, year, null, null, null);
	}

	private static PicksCache.Snapshot snapshot(List<PicksCache.Book> literature, List<PicksCache.Book> science) {
		List<PicksCache.Book> all = new ArrayList<>(literature);
		all.addAll(science);
		return new PicksCache.Snapshot(null, List.of(new PicksCache.Category(PicksCache.ALL_CODE, "전체", all),
				new PicksCache.Category("literature", "문학", literature),
				new PicksCache.Category("science", "과학", science)));
	}

	@Test
	void oneBookPerFieldFromTheTenMostHeldOfTheNewestYear() {
		List<PicksCache.Book> literature = new ArrayList<>();
		for (int i = 0; i < 15; i++) {
			literature.add(book("lit" + i, "literature", 2026, 100 - i, true, true));
		}
		// an older year, no book of ours, no cover: never drawn even though most held
		literature.add(book("old", "literature", 2025, 999, true, true));
		literature.add(book("notOurs", "literature", 2026, 998, false, true));
		literature.add(book("noCover", "literature", 2026, 997, true, false));
		List<PicksCache.Book> science = List.of(book("sci", "science", 2026, 3, true, true));

		Set<String> drawn = new HashSet<>();
		SplittableRandom random = new SplittableRandom(7);
		for (int run = 0; run < 300; run++) {
			List<PicksCache.Book> shelf = MainShelf.choose(snapshot(literature, science), random);
			assertThat(shelf).extracting(PicksCache.Book::category).containsExactly("literature", "science");
			drawn.add(shelf.getFirst().id());
			assertThat(shelf.get(1).id()).isEqualTo("sci");
		}
		// the ten most held 2026 books with a cover, and every one of them in time
		assertThat(drawn).containsExactlyInAnyOrder("lit0", "lit1", "lit2", "lit3", "lit4", "lit5", "lit6", "lit7",
				"lit8", "lit9");
	}

	@Test
	void aFieldWithoutABookThatTakesPartIsLeftOut() {
		List<PicksCache.Book> literature = List.of(book("lit", "literature", 2026, 5, true, true));
		List<PicksCache.Book> science = List.of(book("sci", "science", 2026, 5, false, true));
		assertThat(MainShelf.choose(snapshot(literature, science), new SplittableRandom(1)))
			.extracting(PicksCache.Book::id)
			.containsExactly("lit");
		assertThat(MainShelf.choose(PicksCache.Snapshot.EMPTY, new SplittableRandom(1))).isEmpty();
	}

}
