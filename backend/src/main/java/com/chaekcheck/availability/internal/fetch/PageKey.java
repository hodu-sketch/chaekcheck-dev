package com.chaekcheck.availability.internal.fetch;

import com.chaekcheck.availability.internal.SearchField;

/** Cache key: library, search field, normalized query, page (ADR-0006; field added for later author/publisher search). */
public record PageKey(long libraryId, SearchField field, String query, int page) {
}
