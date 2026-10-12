package com.chaekcheck.library;

/**
 * A library as {@link LibraryRegistry#listAll()} gives it (cycle 13, ADR-0093): what the library-insert command
 * compares the collected libraries with.
 * @param baseUrl site root as stored
 * @param platformType null when the row has none
 * @param lendingModel null for the integrated portals
 */
public record RegisteredLibrary(long id, String name, String baseUrl, PlatformType platformType, Vendor vendor,
		LendingModel lendingModel, int orderNo, boolean enabled) {
}
