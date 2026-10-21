package com.chaekcheck.picks.internal.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The first screen's shelf (user 2026-10-22: "카이스트추천 도서 2026년 버전 중에서 분야별로 하나씩 뽑아서 … 현재
 * 전자도서관 책 보유수 많은 것들만 순위순으로 정렬하고(구독형은 1권씩) 각 분야별 순위 top10 중에서 랜덤하게"): one book per
 * field of a list, from its newest year, drawn at random from the field's {@link #TOP} books that the most libraries hold.
 * A library counts once whatever it lends (a subscription site is one, as an owned one); copies inside a library are
 * not in the catalog. Only books we have and with a cover take part.
 */
public final class MainShelf {

	/** How many of a field's most held books the draw is made from. */
	static final int TOP = 10;

	private MainShelf() {
	}

	/** One book per field in the fields' order; a field without a book that takes part is left out. */
	public static List<PicksCache.Book> choose(PicksCache.Snapshot snapshot, RandomGenerator random) {
		List<PicksCache.Category> fields = snapshot.categories()
			.stream()
			.filter(category -> !PicksCache.ALL_CODE.equals(category.code()))
			.toList();
		Integer newest = fields.stream()
			.flatMap(category -> category.books().stream())
			.map(PicksCache.Book::yearBook)
			.filter(Objects::nonNull)
			.max(Integer::compare)
			.orElse(null);
		List<PicksCache.Book> chosen = new ArrayList<>();
		for (PicksCache.Category field : fields) {
			List<PicksCache.Book> top = field.books()
				.stream()
				.filter(book -> book.bookId() != null && book.coverPath() != null)
				.filter(book -> newest == null || newest.equals(book.yearBook()))
				.sorted(Comparator.comparingInt(MainShelf::held).reversed())
				.limit(TOP)
				.toList();
			if (!top.isEmpty()) {
				chosen.add(top.get(random.nextInt(top.size())));
			}
		}
		return List.copyOf(chosen);
	}

	private static int held(PicksCache.Book book) {
		return book.libraryIds() == null ? 0 : book.libraryIds().length;
	}

}
