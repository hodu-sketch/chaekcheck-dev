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
 * module. Since cycle 24 (ADR-0151) the mode {@code book-texts} puts the AI search's lines in through {@code aisearch}'s
 * {@code AiSearchBookTexts}, with every listed book read by {@code picks}' {@code ListedBooks.loadFromDb()};
 * {@code aisearch} does not know this module. Since cycle 23 step 3-3 (ADR-0149) the crawl PC's for-you data goes in
 * the same way: the new modes {@code neighbours} (book neighbours, {@code full} or {@code add}, checked against the
 * latest import's {@code export_id}) through {@code foryou}'s {@code ForYouTransfer} and {@code tag-dictionary}
 * through {@code picks}' {@code BookTagTransfer}, and {@code book-texts}' kinds {@code for_you_blurb},
 * {@code cover_file} (to {@code foryou}) and {@code tags} (to {@code picks}); {@code foryou} does not know this module.
 * Since cycle 26 (ADR-0161, ADR-0162) every book description is written into {@code catalog} through
 * {@code BookTextsTransfer}: {@code book-texts} (summaries, covers, tags, search lines), {@code tag-dictionary} and the
 * new mode {@code book-profiles}. The {@code book-texts} and {@code book-profiles} files start with the catalog
 * {@code export_id} their book ids are from and are refused for another one unless {@code force}, as
 * {@code neighbours}. Only {@code neighbours} still goes to {@code foryou}; {@code aisearch} is no longer used and
 * leaves the allowed dependencies at deploy 2 (cycle 26 C1).
 */
@ApplicationModule(allowedDependencies = { "catalog", "catalog :: normalize", "library", "picks", "aisearch",
		"foryou" })
package com.chaekcheck.collection;

import org.springframework.modulith.ApplicationModule;
