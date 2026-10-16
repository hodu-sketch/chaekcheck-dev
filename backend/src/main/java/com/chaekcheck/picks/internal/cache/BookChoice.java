package com.chaekcheck.picks.internal.cache;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * One of our books for a recommended book when several match (ADR-0125) [확정]: the one held most, then the first
 * volume (or a book with no volume, {@link ShownTitle}), then the smaller book id. Held = the enabled libraries that
 * do not lend by subscription, plus 1 when any enabled subscription library has it; libraries that are not enabled
 * count for nothing. The collector (scripts/crawl) chooses the fixed editions of the other lists by the same rule.
 */
public final class BookChoice {

	/**
	 * Our book found for a recommended book.
	 * @param title {@code catalog.book.title}, for the first volume
	 */
	public record Candidate(long bookId, String title, long[] libraryIds) {
	}

	private BookChoice() {
	}

	/**
	 * @param lendingKinds every enabled library id with whether it lends by subscription
	 * ({@link com.chaekcheck.library.LibraryCatalog#lendingKinds()})
	 */
	public static Optional<Candidate> choose(Collection<Candidate> candidates, Map<Long, Boolean> lendingKinds) {
		return candidates.stream().min(order(lendingKinds));
	}

	static Comparator<Candidate> order(Map<Long, Boolean> lendingKinds) {
		return Comparator.comparingInt((Candidate c) -> held(c.libraryIds(), lendingKinds))
			.reversed()
			.thenComparing(c -> !ShownTitle.read(c.title() == null ? "" : c.title()).firstVolume())
			.thenComparingLong(Candidate::bookId);
	}

	static int held(long[] libraryIds, Map<Long, Boolean> lendingKinds) {
		int owned = 0;
		boolean subscription = false;
		for (long id : libraryIds) {
			Boolean kind = lendingKinds.get(id);
			if (kind == null) {
				continue;
			}
			if (kind) {
				subscription = true;
			}
			else {
				owned++;
			}
		}
		return owned + (subscription ? 1 : 0);
	}

}
