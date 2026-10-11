/**
 * Collected e-book catalog (ADR-0028): books, contributors and holdings imported once from the collected data
 * (ADR-0031), book search on normalized fields with pg_bigm (ADR-0032), and the holdings of a book for the
 * availability module.
 * <p>
 * Finding books by ISBN (popular books): {@link com.chaekcheck.catalog.BookLookup#byIsbn13} finds our books by ISBN-13 for the
 * popular module (ADR-0074).
 * <p>
 * Separate import (cycle 11, ADR-0078): {@link com.chaekcheck.catalog.CatalogLoad} also exports the new tables as one
 * file on the PC and applies that file on the production database.
 */
package com.chaekcheck.catalog;
