package com.chaekcheck.availability;

import java.util.List;

/**
 * Public API of the availability module for the 「나에게 맞는 책」 tab (cycle 23, ADR-0148): one page of a library
 * site's own popular order, read through the same egress lanes and per-lane host interval as the lookups (one
 * request at a time per lane and host, 1 second apart, and the egress mode, e.g. {@code proxy-only}). Only regular
 * KYOBO sites ({@code orderByKey=loanNmvl}) and YES24 V1 sites ({@code sort=lending}) have such an order; other
 * screens fail without a request. Hosts under the KYOBO subscription suffix ({@code *.dkyobobook.co.kr}) are never
 * requested (cycle 13 R1-6). These requests are not part of the lookup status signal nor of the daily library response
 * counts (handoff C3); no retry, no cache.
 */
public interface RankedListings {

	/**
	 * @param page 1 and up; the page size is the site kind's own ({@link Page#pageSize()})
	 */
	Result fetch(long libraryId, Sort sort, int page);

	enum Sort {

		/** Most borrowed (both sites count all loans so far: a cumulative order, pretest 2026-10-24). */
		LOANS

	}

	sealed interface Result permits Page, Failed {
	}

	/**
	 * @param totalCount the site's total for the whole order
	 * @param pageSize rows per page asked for this site kind
	 */
	record Page(int totalCount, int pageSize, List<Row> rows) implements Result {

		public Page {
			rows = List.copyOf(rows);
		}

	}

	/**
	 * @param reason a short code for logs and the build note: {@code unknown_library}, {@code unsupported_screen},
	 *     {@code blocked_host}, {@code upstream_error}, {@code upstream_timeout}, {@code parse_error}
	 */
	record Failed(String reason, String detail) implements Result {
	}

	/**
	 * One row as the site shows it. The screens show no ISBN.
	 * @param rank 1 and up in the site's order
	 * @param itemKey the site's item number (KYOBO {@code brcd}, YES24 {@code goods_id}), or null when the row links
	 *     no book page
	 * @param loansNow copies on loan now, or null
	 * @param reservations reservations now, or null
	 * @param holdings copies the library owns, or null
	 */
	record Row(int rank, String title, String author, String publisher, String itemKey, Integer loansNow,
			Integer reservations, Integer holdings) {
	}

}
