/**
 * The 「나에게 맞는 책」 tab (cycle 23, ADR-0147~0149): a base list of 40 books built every night from the popular
 * orders of six e-library sites (through {@code availability}'s {@link com.chaekcheck.availability.RankedListings}),
 * the sales rankings and KLWAVE when their sources are on, and the recommended lists; the books similar to what the
 * browser saw, from neighbours the crawl PC computed (KURE-v1). Tab blurbs and cover file keys come from the PC
 * through the {@code collection} import.
 * <p>
 * Owns the {@code foryou} schema. Depends on {@code catalog} ({@code BookCards}: card fields, holding keys, editions of
 * a work; {@code BookLookup}: ISBN and title matching), {@code library} (the user's plan), {@code picks} (the listed
 * books and their tags) and {@code availability} (popular-order pages). No events; other modules are joined by
 * {@code bookId} and {@code libraryId} values only.
 */
@ApplicationModule(allowedDependencies = { "catalog", "library", "picks", "availability" })
package com.chaekcheck.foryou;

import org.springframework.modulith.ApplicationModule;
