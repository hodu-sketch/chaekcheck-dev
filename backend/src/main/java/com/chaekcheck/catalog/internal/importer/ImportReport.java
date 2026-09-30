package com.chaekcheck.catalog.internal.importer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counts logged at the end of an import and written to plan-feedback (handoff 5단계 "쓰기"). */
final class ImportReport {

	long rowsRead;

	final Map<String, Long> skipped = new TreeMap<>();

	final Map<String, Long> kyoboCodes = new TreeMap<>();

	int librariesMatched;

	int librariesWithoutFullSuccess;

	long booksAfterPlatformCode;

	long booksAfterKyoboIsbn;

	long booksAfterNationalBibliography;

	long booksAfterSameText;

	/** Groups that step ④ merged into another group. */
	long sameTextMerged;

	/** Merged books whose groups' dates are 2+ years apart (maybe different editions). */
	long sameTextWideDates;

	final List<String> sameTextWideDateSamples = new ArrayList<>();

	long nationalBibliographyRecords;

	long nationalBibliographyIsbnAttached;

	long nationalBibliographyAmbiguous;

	long books;

	long contributors;

	long holdings;

	Duration readTime = Duration.ZERO;

	Duration groupTime = Duration.ZERO;

	Duration bibliographyTime = Duration.ZERO;

	Duration writeTime = Duration.ZERO;

	void skip(String reason) {
		skipped.merge(reason, 1L, Long::sum);
	}

	long skippedCount() {
		return skipped.values().stream().mapToLong(Long::longValue).sum();
	}

	@Override
	public String toString() {
		return """
				catalog import
				  rows read: %d, skipped: %d %s
				  libraries matched: %d (without a full success, collected_at from last_seen_at: %d)
				  kyobo codes: %s
				  books after (1) platform code: %d, (2) kyobo ISBN: %d, (3) national bibliography: %d, (4) same text: %d
				  (4) same text: groups merged %d, merged books with dates 2+ years apart %d
				  national bibliography: records with ISBN %d, ISBN attached %d, ambiguous keys %d
				  written: books %d, contributors %d, holdings %d
				  time: read %s, group %s, bibliography %s, write %s""".formatted(rowsRead, skippedCount(), skipped,
				librariesMatched, librariesWithoutFullSuccess, kyoboCodes, booksAfterPlatformCode, booksAfterKyoboIsbn,
				booksAfterNationalBibliography, booksAfterSameText, sameTextMerged, sameTextWideDates, nationalBibliographyRecords, nationalBibliographyIsbnAttached,
				nationalBibliographyAmbiguous, books, contributors, holdings, readTime, groupTime, bibliographyTime,
				writeTime);
	}

}
