/**
 * AI book search (cycle 24, ADR-0150): a sentence in, books from the recommended lists that the user's plan libraries
 * hold out, with text written for the question by Claude Haiku 5.5. Owns the {@code aisearch} schema:
 * {@code search_book} (the listed books' title and author copy with the search line, matched by keywords on
 * {@code search_text} with pg_bigm, ADR-0151) and {@code daily_usage} (the daily cost cap, ADR-0152).
 * <p>
 * One public Java type so far, {@link com.chaekcheck.aisearch.AiSearchBookTexts}: the {@code collection} import's
 * {@code book-texts} mode puts the search lines in through it. The question text is never stored anywhere on our
 * server (ADR-0152).
 * <p>
 * Allowed dependencies (design-delta "모듈"): {@code picks} ({@code ListedBooks}), {@code library}
 * ({@code LibraryCatalog.planSearch}) and {@code usagelog} ({@code UsageLog}), used from the search flow (handoff
 * steps 5 to 8). No events.
 */
@ApplicationModule(allowedDependencies = { "picks", "library", "usagelog" })
package com.chaekcheck.aisearch;

import org.springframework.modulith.ApplicationModule;
