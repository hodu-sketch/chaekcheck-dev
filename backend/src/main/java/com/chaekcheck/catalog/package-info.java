/**
 * Collected e-book catalog (ADR-0028): books, contributors and holdings imported once from the collected data
 * (ADR-0031), book search on normalized fields with pg_bigm (ADR-0032), and the holdings of a book for the
 * availability module.
 * <p>
 * Finding books for the recommended lists: {@link com.chaekcheck.catalog.BookLookup} finds our books by ISBN-13, then
 * by title and first author, and reads them by id, for the picks module (ADR-0090, ADR-0124). The import matches its
 * lists against the prepared tables through {@link com.chaekcheck.catalog.CatalogLoad#preparedLookup()}. Since cycle 24
 * {@link com.chaekcheck.catalog.BookAuthors} gives picks our books' author text for the AI book search.
 * <p>
 * The 「나에게 맞는 책」 tab (cycle 23, ADR-0149): {@link com.chaekcheck.catalog.BookCards} gives the foryou module the
 * card fields, our book of a holding a library site lists, and the representative edition of a work.
 * <p>
 * The book descriptions in one place (cycle 26, ADR-0161): the 책첵 summary, an edition's cover, the book profile, the
 * AI search's line and keyword text, and the tags ({@code catalog.book_summary}, {@code book_cover},
 * {@code book_profile}, {@code book_search}, {@code tag_dictionary}, {@code book_tag}). Other modules read them through
 * {@link com.chaekcheck.catalog.BookTexts}, {@link com.chaekcheck.catalog.CoverUrls} (the covers are on CloudFront,
 * ADR-0163), {@link com.chaekcheck.catalog.BookProfiles} and {@link com.chaekcheck.catalog.AiSearchIndex}; only the
 * {@code collection} import writes them, through {@link com.chaekcheck.catalog.BookTextsTransfer}. The rows are kept by
 * book id without a foreign key; the catalog swap deletes those of book ids the new catalog no longer has (ADR-0162).
 * <p>
 * Separate import (cycle 11, ADR-0078): {@link com.chaekcheck.catalog.CatalogLoad} also exports the new tables as one
 * file on the PC and applies that file on the production database.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "library", "usagelog" })
package com.chaekcheck.catalog;
