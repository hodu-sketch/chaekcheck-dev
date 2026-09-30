package com.chaekcheck.availability.internal.resolve;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.library.SearchTarget;

/**
 * Decides, row by row, whether a book can be borrowed now (ADR-0019, design-delta "판정 방식 인터페이스"). A row is
 * decided at once, or marked PENDING with a future that completes later. Extra requests, their deadline and the
 * value used when they fail belong to the implementation; callers only see PENDING rows and their futures.
 * Implementations are chosen per screen kind by {@link AvailabilityResolvers}.
 */
public interface AvailabilityResolver {

	/**
	 * @param detailDeadline the latest time a PENDING row may take; its future completes by then (UNKNOWN when
	 * the lookup did not finish)
	 */
	Resolution resolve(SearchTarget target, LibraryPage page, Instant detailDeadline);

	/** A row's final availability. */
	record Decision(Availability availability, AvailabilityBasis basis) {
	}

	/** A PENDING row of the page, found by its rank. */
	record PendingRow(int rank, CompletableFuture<Decision> decision) {
	}

	/**
	 * @param page every row carries an availability; PENDING rows are listed in {@code pending}
	 */
	record Resolution(LibraryPage page, List<PendingRow> pending) {

		public Resolution {
			pending = List.copyOf(pending);
		}

		public boolean hasPending() {
			return !pending.isEmpty();
		}

		/** Waits for every PENDING row and returns the page with the final values (the "more" API). */
		public LibraryPage completedPage() {
			if (pending.isEmpty()) {
				return page;
			}
			CompletableFuture.allOf(pending.stream().map(PendingRow::decision).toArray(CompletableFuture[]::new))
				.join();
			return page.withItems(items -> items.stream().map(item -> pending.stream()
				.filter(row -> row.rank() == item.rank())
				.findFirst()
				.map(row -> {
					Decision d = row.decision().join();
					return item.withAvailability(d.availability(), d.basis());
				})
				.orElse(item)).toList());
		}

	}

}
