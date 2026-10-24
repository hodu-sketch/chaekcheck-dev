package com.chaekcheck.aisearch.internal.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.chaekcheck.picks.ListedBooks.ListedBook;

/**
 * The books the AI search may answer with for one request (cycle 24 handoff step 5): the listed books held by at least
 * one of the user's plan libraries (spec R3-3), each with how many hold it. Counted in memory from
 * {@code ListedBooks.current()}, no database call.
 */
public final class PlanTargets {

	private PlanTargets() {
	}

	/**
	 * @param bookId a listed book
	 * @param planCount how many of the plan libraries hold it, 1 or more
	 */
	public record Target(long bookId, int planCount) {
	}

	/**
	 * @param books the listed books
	 * @param planLibraries the user's plan library ids
	 * @param excluded books to leave out (shown before, spec R11-4)
	 */
	public static List<Target> of(List<ListedBook> books, Set<Long> planLibraries, Set<Long> excluded) {
		List<Target> targets = new ArrayList<>();
		if (planLibraries.isEmpty()) {
			return targets;
		}
		for (ListedBook book : books) {
			if (excluded.contains(book.bookId())) {
				continue;
			}
			int count = book.countHeldBy(planLibraries);
			if (count > 0) {
				targets.add(new Target(book.bookId(), count));
			}
		}
		return targets;
	}

}
