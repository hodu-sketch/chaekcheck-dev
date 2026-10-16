/**
 * Collected data to the service (ADR-0052, ADR-0060): the link from a collection DB library id to a service library
 * id, and the whole catalog import (read the collection DB copy in two passes, map ids, filter, group the same
 * books, normalize, {@code book.library_ids}, import records). No public Java API. Two commands run as their own
 * profiles and exit: the import ({@code collection-import}) and, since cycle 13, the library insert
 * ({@code library-insert}, ADR-0084: preview report, then insert with the report id; libraries go in through
 * {@code library}'s {@code LibraryRegistry}, links and run rows here). One public REST endpoint (cycle 13, ADR-0093):
 * {@code GET /api/v1/join-surveys/latest}. It writes the catalog through {@code catalog}'s {@code CatalogLoad}, reads
 * the enabled libraries ({@code LibraryCatalog.enabledLibraryRefs()}) and, for the insert, every library
 * ({@code LibraryRegistry.listAll()}) from {@code library}; it never reads {@code library}'s tables. Since cycle 21
 * (ADR-0123) the import also carries Lee Dong-jin's picks and the SNU 100 through {@code picks}'
 * {@code CuratedListTransfer}: checked and matched on the PC, put in after the swap; {@code picks} does not know this
 * module.
 */
@ApplicationModule(allowedDependencies = { "catalog", "catalog :: normalize", "library", "picks" })
package com.chaekcheck.collection;

import org.springframework.modulith.ApplicationModule;
