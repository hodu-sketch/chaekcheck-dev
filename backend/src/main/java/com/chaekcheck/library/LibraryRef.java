package com.chaekcheck.library;

/**
 * An enabled library as the collection import checks it: its link points at it and the base URL is the same.
 * @param baseUrl site root, e.g. {@code https://www.ghlib.go.kr:446}
 */
public record LibraryRef(long id, String baseUrl, PlatformType platformType) {
}
