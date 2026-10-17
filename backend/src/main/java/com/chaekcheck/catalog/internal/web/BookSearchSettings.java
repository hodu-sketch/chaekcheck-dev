package com.chaekcheck.catalog.internal.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code GET /api/v1/books} settings, {@code chaekcheck.catalog.books.*} (cycle 20, ADR-0132, spec R15).
 * @param defaultPageSize books on a page when {@code pageSize} is not sent [확정 10]; the maximum stays
 * {@link BookParams#MAX_PAGE_SIZE} ({@code CHAEKCHECK_CATALOG_BOOKS_DEFAULTPAGESIZE};
 * docs/maintenance/settings-index.md)
 */
@ConfigurationProperties("chaekcheck.catalog.books")
public record BookSearchSettings(int defaultPageSize) {

	public BookSearchSettings {
		if (defaultPageSize < 1 || defaultPageSize > BookParams.MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("chaekcheck.catalog.books.default-page-size must be 1 to "
					+ BookParams.MAX_PAGE_SIZE + ": " + defaultPageSize);
		}
	}

}
