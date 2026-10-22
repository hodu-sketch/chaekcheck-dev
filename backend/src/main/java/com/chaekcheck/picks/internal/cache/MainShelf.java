package com.chaekcheck.picks.internal.cache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The first screen's shelf (user 2026-10-22: "카이스트추천 도서 2026년 버전 중에서 분야별로 하나씩 뽑아서 … 현재
 * 전자도서관 책 보유수 많은 것들만 순위순으로 정렬하고(구독형은 1권씩) 각 분야별 순위 top10 중에서 랜덤하게"): one book per
 * field of a list, drawn at random from the field's {@link #TOP} books that the most libraries hold. A library counts
 * once whatever it lends (a subscription site is one, as an owned one); copies inside a library are not in the catalog.
 * Only books we have, with a cover, and held by at least one library anyone may join take part ("모두 가입 가능한 도서관 중에
 * 한 권이라도 있어야돼"). The newest year first; a field with fewer than {@link #TOP} such books takes the years before it
 * too, one at a time ("책이 너무 없으면 2026말고 2025 … 2024도 괜찮아").
 */
public final class MainShelf {

	/** How many of a field's most held books the draw is made from. */
	static final int TOP = 10;

	private MainShelf() {
	}

	/**
	 * One book per field in the fields' order; a field without a book that takes part is left out.
	 * @param open the ids of the libraries anyone may join
	 */
	public static List<PicksCache.Book> choose(PicksCache.Snapshot snapshot, Set<Long> open, RandomGenerator random) {
		List<PicksCache.Category> fields = snapshot.categories()
			.stream()
			.filter(category -> !PicksCache.ALL_CODE.equals(category.code()))
			.toList();
		List<Integer> years = fields.stream()
			.flatMap(category -> category.books().stream())
			.map(PicksCache.Book::yearBook)
			.filter(Objects::nonNull)
			.distinct()
			.sorted(Comparator.reverseOrder())
			.toList();
		List<PicksCache.Book> chosen = new ArrayList<>();
		for (PicksCache.Category field : fields) {
			List<PicksCache.Book> pool = field.books()
				.stream()
				.filter(book -> book.bookId() != null && book.coverPath() != null && heldOpen(book, open))
				.toList();
			List<PicksCache.Book> taking = new ArrayList<>();
			if (years.isEmpty()) {
				taking.addAll(pool);
			}
			for (Integer year : years) {
				pool.stream().filter(book -> year.equals(book.yearBook())).forEach(taking::add);
				if (taking.size() >= TOP) {
					break;
				}
			}
			List<PicksCache.Book> top = taking.stream()
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

	private static boolean heldOpen(PicksCache.Book book, Set<Long> open) {
		if (book.libraryIds() == null) {
			return false;
		}
		for (long id : book.libraryIds()) {
			if (open.contains(id)) {
				return true;
			}
		}
		return false;
	}

}
