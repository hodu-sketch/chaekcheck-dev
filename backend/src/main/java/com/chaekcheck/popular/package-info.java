/**
 * Popular books (cycle 10): last month's sales ranking of the publishing distribution network
 * (bnk.kpipa.or.kr, ADR-0072), "전체" and 16 categories with 50 books each (ADR-0077), shown with whether the
 * user's plan libraries hold the e-book. REST only, no public Java types; no other module depends on this one.
 * <p>
 * Responsibilities: fetching the ranking pages, category sales counts and small covers once a month on its own
 * single-thread executor (decided daily at 05:00 Asia/Seoul and at start-up, ADR-0073), storing them in the
 * {@code popular} schema with {@code JdbcClient}, keeping the covers of the latest two months and serving them
 * (ADR-0075), one shared cache of the latest month with our books found by ISBN (ADR-0074), and
 * {@code GET /api/v1/popular-books} and {@code GET /api/v1/popular-books/covers/{coverId}}.
 * <p>
 * Depends only on public types: {@code catalog} ({@code BookLookup.byIsbn13}: ranking books and our books are
 * matched by ISBN when shown, never stored as pairs) and {@code library} ({@code LibraryCatalog.planSearch} for the
 * user's plan). No events.
 */
@ApplicationModule(allowedDependencies = { "catalog", "library" })
package com.chaekcheck.popular;

import org.springframework.modulith.ApplicationModule;
