/**
 * AI book search (cycle 24, ADR-0150): a sentence in, books from the recommended lists and, since 2026-10-25, the
 * books held by the most libraries (user: "보유량 많은 3만 권") that the user's plan libraries hold out, with text
 * written for the question by Claude Haiku 5.5. Owns the {@code aisearch} schema: {@code daily_usage} (the daily cost
 * cap, ADR-0152) and, until deploy 2 of cycle 26 drops it, the old {@code search_book}, no longer read.
 * <p>
 * Since cycle 26 (ADR-0165) the search books, their lines and the keyword match are {@code catalog}'s
 * ({@code AiSearchIndex} over {@code catalog.book_search}), the cards' covers and summaries come from
 * {@code catalog.BookTexts} (read while the call runs), and the first call's book profiles from
 * {@code catalog.BookProfiles} once at start. No public Java type. The question text is never stored anywhere on our
 * server (ADR-0152).
 * <p>
 * Allowed dependencies (design-delta "모듈"): {@code picks} ({@code ListedBooks}), {@code library}
 * ({@code LibraryCatalog.planSearch}) and {@code usagelog} ({@code UsageLog}), used from the search flow (handoff
 * steps 5 to 8), {@code foryou} ({@code BookNeighbours}: a follow-up question about the books shown also gets their
 * neighbours as candidates, user 2026-10-25) and {@code catalog} (the above and {@code BookCards}: the candidates'
 * publish year for the second call, user 2026-10-26 "출간시기도"). No events.
 */
@ApplicationModule(allowedDependencies = { "picks", "library", "usagelog", "foryou", "catalog" })
package com.chaekcheck.aisearch;

import org.springframework.modulith.ApplicationModule;