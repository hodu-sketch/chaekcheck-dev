/**
 * Collected data to the service (ADR-0052, ADR-0060): the link from a collection DB library id to a service library
 * id, and the whole catalog import (read the collection DB copy in two passes, map ids, filter, group the same
 * books, normalize, {@code book.library_ids}, import records). No public API: the import runs as its own profile
 * ({@code collection-import}). It writes the catalog through {@code catalog}'s {@code CatalogLoad} and reads only the
 * enabled libraries ({@code LibraryCatalog.enabledLibraryRefs()}) from {@code library}.
 */
@ApplicationModule(allowedDependencies = { "catalog", "catalog :: normalize", "library" })
package com.chaekcheck.collection;

import org.springframework.modulith.ApplicationModule;
