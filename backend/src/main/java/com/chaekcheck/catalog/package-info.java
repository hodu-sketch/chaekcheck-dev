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
 * Separate import (cycle 11, ADR-0078): {@link com.chaekcheck.catalog.CatalogLoad} also exports the new tables as one
 * file on the PC and applies that file on the production database.
 */
package com.chaekcheck.catalog;
