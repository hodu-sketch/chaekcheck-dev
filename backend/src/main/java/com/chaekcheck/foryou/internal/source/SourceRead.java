package com.chaekcheck.foryou.internal.source;

import java.util.List;

/**
 * What one site or feed gave for one build: its rows, or why it failed (then the rows are empty).
 * @param source {@code kyobo_elib}, {@code yes24_elib}, {@code kpipa_rank}, {@code kpipa_200}, {@code klwave}
 * @param siteLibraryId the e-library site ({@code library.id}), null for the feeds
 * @param period the feed's period ({@code day}, {@code week}, {@code month}, {@code quarter}), null for sites
 * @param failure a short reason, or null when the read worked
 */
public record SourceRead(String source, Long siteLibraryId, String period, List<SourceRow> rows, String failure) {

	public SourceRead {
		rows = List.copyOf(rows);
	}

	/** {@code kyobo_elib:31} or {@code kpipa_rank}: one unit of the build note and of leaving out. */
	public String unit() {
		return siteLibraryId == null ? source : source + ":" + siteLibraryId;
	}

}
