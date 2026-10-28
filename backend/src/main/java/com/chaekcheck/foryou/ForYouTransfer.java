package com.chaekcheck.foryou;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * What the crawl PC computes for the 「나에게 맞는 책」 tab, carried by the {@code collection} import (cycle 23, ADR-0149):
 * book neighbours (mode {@code neighbours}: {@code full} swaps the whole table in, {@code add} upserts). Only
 * {@code collection} calls it. The tab blurbs and cover file keys go to {@code catalog} since cycle 26 (ADR-0161).
 */
public interface ForYouTransfer {

	/**
	 * Copies every row into a new table and swaps it in, in one short transaction at the end; a failure before the swap
	 * leaves the old table as it was.
	 * @return rows written
	 */
	long replaceAllNeighbours(Iterator<Neighbours> rows, LocalDate computedOn);

	/**
	 * Adds the rows or replaces the books' rows ({@code ON CONFLICT (book_id) DO UPDATE}).
	 * @return rows written
	 */
	long upsertNeighbours(Iterator<Neighbours> rows, LocalDate computedOn);

	/**
	 * The neighbours of one book, nearest first: 1 to 20 ids, not the book itself, each once; the book id fits the
	 * 4-byte neighbour ids (ADR-0149).
	 * @throws IllegalArgumentException when a rule fails
	 */
	record Neighbours(long bookId, int[] neighbourIds) {

		public static final int MAX = 20;

		public Neighbours {
			if (bookId < 1 || bookId > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("book id out of range: " + bookId);
			}
			if (neighbourIds.length < 1 || neighbourIds.length > MAX) {
				throw new IllegalArgumentException("book " + bookId + " has " + neighbourIds.length + " neighbours");
			}
			Set<Integer> seen = new HashSet<>();
			for (int id : neighbourIds) {
				if (id < 1) {
					throw new IllegalArgumentException("book " + bookId + " has a neighbour id " + id);
				}
				if (id == bookId) {
					throw new IllegalArgumentException("book " + bookId + " is its own neighbour");
				}
				if (!seen.add(id)) {
					throw new IllegalArgumentException("book " + bookId + " has neighbour " + id + " twice");
				}
			}
			neighbourIds = neighbourIds.clone();
		}

		@Override
		public int[] neighbourIds() {
			return neighbourIds.clone();
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Neighbours that && bookId == that.bookId
					&& Arrays.equals(neighbourIds, that.neighbourIds);
		}

		@Override
		public int hashCode() {
			return Long.hashCode(bookId) * 31 + Arrays.hashCode(neighbourIds);
		}

	}

}
