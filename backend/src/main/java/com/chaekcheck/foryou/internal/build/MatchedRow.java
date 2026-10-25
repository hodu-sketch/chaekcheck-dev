package com.chaekcheck.foryou.internal.build;

import com.chaekcheck.foryou.internal.source.SourceRow;

/**
 * A source row with our book, or {@code bookId} null when no book matched.
 * @param source {@code kyobo_elib}, {@code yes24_elib}, {@code kpipa_rank}, {@code kpipa_200}, {@code klwave}
 */
public record MatchedRow(String source, Long siteLibraryId, String period, SourceRow row, Long bookId) {
}
