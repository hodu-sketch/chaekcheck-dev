package com.chaekcheck.library;

/**
 * An enabled library as the catalog importer compares it with the collected data: same id and base URL only.
 * @param baseUrl site root, e.g. {@code https://www.ghlib.go.kr:446}
 */
public record LibraryRef(long id, String baseUrl, PlatformType platformType) {
}
