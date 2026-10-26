/**
 * AI book search (cycle 24, ADR-0150): a sentence in, books from the recommended lists and, since 2026-10-25, the
 * books held by the most libraries (user: "보유량 많은 3만 권") that the user's plan libraries hold out, with text
 * written for the question by Claude Haiku 5.5. Owns the {@code aisearch} schema: {@code search_book} (those books'
 * title, author and library ids copy with the search line, matched by keywords on {@code search_text} with pg_bigm,
 * ADR-0151) and {@code daily_usage} (the daily cost cap, ADR-0152).
 * <p>
 * One public Java type so far, {@link com.chaekcheck.aisearch.AiSearchBookTexts}: the {@code collection} import's
 * {@code book-texts} mode puts the search lines in through it. The question text is never stored anywhere on our
 * server (ADR-0152).
 * <p>
 * Allowed dependencies (design-delta "모듈"): {@code picks} ({@code ListedBooks}), {@code library}
 * ({@code LibraryCatalog.planSearch}) and {@code usagelog} ({@code UsageLog}), used from the search flow (handoff
 * steps 5 to 8), and {@code foryou} ({@code BookNeighbours}: a follow-up question about the books shown also gets
 * their neighbours as candidates, user 2026-10-25), and {@code catalog} ({@code BookCards}: the candidates' publish
 * year for the second call, user 2026-10-26 "출간시기도"). No events.
 */
@ApplicationModule(allowedDependencies = { "picks", "library", "usagelog", "foryou", "catalog" })
package com.chaekcheck.aisearch;

import org.springframework.modulith.ApplicationModule;
