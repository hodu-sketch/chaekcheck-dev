/**
 * Librarian picks (cycle 14, replaces the popular books of cycle 10): the National Library of Korea's 사서추천도서,
 * the latest twelve recommendation months, by field (ADR-0089; twelve by user decision 2026-10-12), shown with whether the user's plan libraries hold the
 * e-book. REST only, no public Java types; no other module depends on this one.
 * <p>
 * Responsibilities: fetching the list with one Open API request ({@code saseoApi.do}) on its own single-thread
 * executor, decided daily at 05:00 Asia/Seoul and at start-up: Mondays, every day after a failure until one succeeds,
 * and catching up a missed week (ADR-0092); storing it in the {@code picks} schema with {@code JdbcClient}; fetching
 * the new covers from {@code www.nl.go.kr/afile/}, made 200px JPEG and served by us (ADR-0091); one shared cache with
 * our books matched by ISBN, then by title and first author (ADR-0090); {@code GET /api/v1/picks} and
 * {@code GET /api/v1/picks/covers/{coverId}}.
 * <p>
 * The API key is a secret (spec R1-7): it comes from the environment only and is never logged, stored or shown; the
 * request is logged without it.
 * <p>
 * Depends only on public types: {@code catalog} ({@code BookLookup}: picks and our books are matched when the cache is
 * built, never stored as pairs), {@code catalog :: normalize} ({@code Isbn.toIsbn13} for the API's ISBNs) and
 * {@code library} ({@code LibraryCatalog.planSearch} for the user's plan). No events.
 */
@ApplicationModule(allowedDependencies = { "catalog", "catalog :: normalize", "library" })
package com.chaekcheck.picks;

import org.springframework.modulith.ApplicationModule;
