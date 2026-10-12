package com.chaekcheck.picks.internal.cache;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * One of our books for a pick when several match (spec R3-2, ADR-0090) [확정]: the one held by the most libraries,
 * then the smaller book id. Used for both matches: by ISBN (several books with the same ISBN [안]) and by title and
 * author.
 */
final class BookChoice {

	/** Our book found for a pick. */
	record Candidate(long bookId, long[] libraryIds) {
	}

	static final Comparator<Candidate> ORDER = Comparator
		.comparingInt((Candidate c) -> c.libraryIds().length)
		.reversed()
		.thenComparingLong(Candidate::bookId);

	private BookChoice() {
	}

	static Optional<Candidate> choose(Collection<Candidate> candidates) {
		return candidates.stream().min(ORDER);
	}

}
