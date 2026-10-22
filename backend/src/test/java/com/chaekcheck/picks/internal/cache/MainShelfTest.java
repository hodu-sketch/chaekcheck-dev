package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

class MainShelfTest {

	/** Library 1 is the one anyone may join; a book held by it counts library 1 among its {@code held} libraries. */
	private static final Set<Long> OPEN = Set.of(1L);

	private static PicksCache.Book book(String id, String field, int year, int held, boolean ours, boolean cover,
			boolean openHolds) {
		long[] libraries = new long[held];
		for (int i = 0; i < held; i++) {
			libraries[i] = openHolds ? i + 1 : i + 2;
		}
		return new PicksCache.Book(id, field, null, id, null, cover ? "/covers/" + id + ".jpg" : null,
				ours ? 1L : null, libraries, year, null, null, null);
	}

	private static PicksCache.Book book(String id, String field, int year, int held) {
		return book(id, field, year, held, true, true, true);
	}

	private static PicksCache.Snapshot snapshot(List<PicksCache.Book> literature, List<PicksCache.Book> science) {
		List<PicksCache.Book> all = new ArrayList<>(literature);
		all.addAll(science);
		return new PicksCache.Snapshot(null, List.of(new PicksCache.Category(PicksCache.ALL_CODE, "전체", all),
				new PicksCache.Category("literature", "문학", literature),
				new PicksCache.Category("science", "과학", science)));
	}

	private static Set<String> drawn(PicksCache.Snapshot snapshot, int index) {
		Set<String> drawn = new HashSet<>();
		SplittableRandom random = new SplittableRandom(7);
		for (int run = 0; run < 400; run++) {
			List<PicksCache.Book> shelf = MainShelf.choose(snapshot, OPEN, random);
			drawn.add(shelf.get(index).id());
		}
		return drawn;
	}

	@Test
	void oneBookPerFieldFromTheTenMostHeldOfTheNewestYear() {
		List<PicksCache.Book> literature = new ArrayList<>();
		for (int i = 0; i < 15; i++) {
			literature.add(book("lit" + i, "literature", 2026, 100 - i));
		}
		// an older year (the newest has ten), no book of ours, no cover, not held by a library anyone may join: never
		// drawn even though most held
		literature.add(book("old", "literature", 2025, 999));
		literature.add(book("notOurs", "literature", 2026, 998, false, true, true));
		literature.add(book("noCover", "literature", 2026, 997, true, false, true));
		literature.add(book("notOpen", "literature", 2026, 996, true, true, false));
		List<PicksCache.Book> science = List.of(book("sci", "science", 2026, 3));

		PicksCache.Snapshot snapshot = snapshot(literature, science);
		assertThat(MainShelf.choose(snapshot, OPEN, new SplittableRandom(1))).extracting(PicksCache.Book::category)
			.containsExactly("literature", "science");
		assertThat(drawn(snapshot, 0)).containsExactlyInAnyOrder("lit0", "lit1", "lit2", "lit3", "lit4", "lit5",
				"lit6", "lit7", "lit8", "lit9");
	}

	@Test
	void aFieldWithFewBooksInTheNewestYearTakesTheYearsBefore() {
		// three in 2026, five in 2025, six in 2024: 2026 and 2025 are not ten, so 2024 joins; the ten most held of them
		List<PicksCache.Book> literature = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			literature.add(book("y26-" + i, "literature", 2026, 10 + i));
		}
		for (int i = 0; i < 5; i++) {
			literature.add(book("y25-" + i, "literature", 2025, 50 + i));
		}
		for (int i = 0; i < 6; i++) {
			literature.add(book("y24-" + i, "literature", 2024, 30 + i));
		}
		List<PicksCache.Book> science = List.of(book("sci", "science", 2026, 3));
		Set<String> drawn = drawn(snapshot(literature, science), 0);
		// the 14 candidates minus the four least held (y26-0..2 with 10–12, y24-0 with 30)
		assertThat(drawn).containsExactlyInAnyOrder("y25-0", "y25-1", "y25-2", "y25-3", "y25-4", "y24-1", "y24-2",
				"y24-3", "y24-4", "y24-5");
	}

	@Test
	void aFieldWithoutABookThatTakesPartIsLeftOut() {
		List<PicksCache.Book> literature = List.of(book("lit", "literature", 2026, 5));
		List<PicksCache.Book> science = List.of(book("sci", "science", 2026, 5, true, true, false));
		assertThat(MainShelf.choose(snapshot(literature, science), OPEN, new SplittableRandom(1)))
			.extracting(PicksCache.Book::id)
			.containsExactly("lit");
		assertThat(MainShelf.choose(PicksCache.Snapshot.EMPTY, OPEN, new SplittableRandom(1))).isEmpty();
	}

}
