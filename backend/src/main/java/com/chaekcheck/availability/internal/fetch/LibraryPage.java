package com.chaekcheck.availability.internal.fetch;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One page of one library's results (design-delta "페이지 객체"). Same shape as the stream's library-result event.
 * @param loanRate loaned total / holdings total on this page; null when holdings total is 0 or the screen shows no
 * holdings
 * @param cacheAgeSeconds seconds since {@code fetchedAt} by the server clock; 0 when not cached
 */
public record LibraryPage(long libraryId, String query, int page, int pageSize, int totalCount, boolean hasMore,
		Instant fetchedAt, boolean cached, long cacheAgeSeconds, Double loanRate, String librarySearchUrl,
		List<Item> items) {

	public LibraryPage {
		items = List.copyOf(items);
	}

	public LibraryPage withItems(UnaryOperator<List<Item>> change) {
		return new LibraryPage(libraryId, query, page, pageSize, totalCount, hasMore, fetchedAt, cached,
				cacheAgeSeconds, loanRate, librarySearchUrl, change.apply(items));
	}

	/**
	 * @param rank position in the library's own result order: (page - 1) x pageSize + position
	 * @param holdings null on screens without holdings (portal, subscription); {@code available} is null then too
	 * @param titleKey title comparison key (ADR-0018); rows with the same key are the same title for sorting
	 * @param availability AVAILABLE, RESERVABLE, PENDING or UNKNOWN, decided by an {@code AvailabilityResolver}
	 * @param detailUrl the book's page on the library site; null when the site did not link one
	 * @param siteVerdict what the site wrote as availability; internal, not sent
	 */
	public record Item(int rank, String title, String author, String publisher, String supplier, Integer holdings,
			Integer loaned, Integer reservations, Integer available, String titleKey, Availability availability,
			AvailabilityBasis availabilityBasis, String detailUrl, @JsonIgnore SiteVerdict siteVerdict) {

		public Item withAvailability(Availability availability, AvailabilityBasis basis) {
			return new Item(rank, title, author, publisher, supplier, holdings, loaned, reservations, available,
					titleKey, availability, basis, detailUrl, siteVerdict);
		}

	}

	/** Whether a row can be borrowed now (design-delta "대출 가능 여부 판정"). */
	public enum Availability {
		AVAILABLE, RESERVABLE,
		/** Decided later; a row-verdict event follows. Only in streams. */
		PENDING,
		/** Could not be decided (detail lookup failed, timed out or showed an unknown value). */
		UNKNOWN
	}

	/** What the availability was decided from. */
	public enum AvailabilityBasis {
		/** holdings - loaned */
		COUNT,
		/** the site's verdict in the search list */
		SITE_LIST,
		/** blank verdict and nobody has it on loan */
		LOANED_ZERO,
		/** the site's verdict on the book's detail page */
		SITE_DETAIL,
		/** subscription: any number of users can borrow at once */
		SUBSCRIPTION
	}

}
