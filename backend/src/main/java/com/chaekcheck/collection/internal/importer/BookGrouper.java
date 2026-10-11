package com.chaekcheck.collection.internal.importer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

import com.chaekcheck.catalog.normalize.Contributors.Contributor;
import com.chaekcheck.catalog.normalize.Contributors.Role;
import com.chaekcheck.catalog.normalize.Isbn;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * Groups collected rows into books (research same-book-isbn 5절), called by {@link CatalogImporter} in this order:
 * {@link #add} for every row of the first pass, {@link #groupByCodes}, {@link #bibliographyKeys} and
 * {@link #attachBibliography} (step ③, when the national bibliography is read), {@link #mergeSameText},
 * {@link #forEachBook}, then {@link #bookOf} for every row of the second pass.
 * <p>
 * Cycle 11 (ADR-0079): three ways, chosen by {@code chaekcheck.collection.import.grouping}: {@code current}
 * ({@link BookGrouping}), {@code compact} and {@code sql}. They must give the same books in the same order, and so the
 * same book and holding ids; the rules every way follows:
 * <ol>
 * <li>Step ① key: {@link #unitKey}. A unit is the rows with one key.</li>
 * <li>Step ②: units are joined through every Kyobo-style ISBN they have ({@link #kyoboIsbn}), not only their most
 * common one; then a group's ISBN is the one most of its rows have.</li>
 * <li>Step ③: groups in the order of their first unit; a group without an ISBN whose key ({@link #key}) has one
 * national bibliography ISBN gets it and joins the first group with that ISBN.</li>
 * <li>Step ④: the same-text keys are all computed from the groups as they are when step ④ starts.</li>
 * <li>Joining keeps the smaller unit number as the group; its ISBN comes first.</li>
 * <li>A most common value: most rows, ties to the smallest by {@link String#compareTo}; the date by
 * {@code PublishedDates.representative}.</li>
 * <li>Books in the order of their first unit, which is the order of their first row.</li>
 * </ol>
 * The {@link ImportReport} counts of these steps are filled the same way too.
 */
interface BookGrouper extends AutoCloseable {

	/**
	 * Screen kinds whose item keys are one shared numbering. Sites may change their numbering, so this is checked
	 * again with every library list check (docs/library-maintenance.md 4-11, scripts/library/check_code_spaces.py).
	 */
	Set<String> SHARED_CODE_KINDS = Set.of("KYOBO_V1", "KYOBO_SUBSCRIPTION_V1", "YES24_V1", "YES24_V2", "YES24_V3");

	char SEP = '\u0001';

	/**
	 * One book to write.
	 * @param libraryIds service libraries holding it, sorted (ADR-0055)
	 */
	record Book(String title, String authorDisplay, String publisher, String titleNorm, String mainTitleNorm,
			String publisherNorm, LocalDate publishedOn, String isbn13, String contentType,
			List<Contributor> contributors, long[] libraryIds) {
	}

	/** First pass: counts one row. */
	void add(Row row, ImportReport report);

	/** Steps ① and ②. */
	void groupByCodes(ImportReport report);

	/**
	 * Keys (main title, first author, publisher) of groups without an ISBN, for the national bibliography lookup.
	 * Groups without an author or publisher have no key (not merged when unsure).
	 */
	Set<String> bibliographyKeys();

	/** Step ③. */
	void attachBibliography(Map<String, String> isbnByKey, ImportReport report);

	/** Step ④. */
	void mergeSameText(ImportReport report);

	/** Gives every book to {@code sink} in the order of its first row; the sink returns the book's id. */
	void forEachBook(ToLongFunction<Book> sink);

	/** Second pass, after {@link #forEachBook}: the id the sink gave the row's book. */
	long bookOf(Row row);

	/** Frees what the grouping holds outside the heap (the {@code sql} way's temporary tables). */
	@Override
	default void close() {
	}

	/** Step ①: shared-numbering screens share one key space, the others one per library. */
	static String unitKey(Row row) {
		String space = SHARED_CODE_KINDS.contains(row.library().platformType()) ? "SHARED"
				: "LIB" + row.library().serviceId();
		return space + SEP + row.itemKey();
	}

	/** The comparison key; null when a part is missing. Shared with {@link NationalBibliography}. */
	static String key(String mainTitleNorm, String authorExact, String publisherNorm) {
		if (mainTitleNorm == null || mainTitleNorm.isEmpty() || authorExact == null || authorExact.isEmpty()
				|| publisherNorm == null || publisherNorm.isEmpty()) {
			return null;
		}
		return mainTitleNorm + SEP + authorExact + SEP + publisherNorm;
	}

	/** Exact name of the first author (AUTHOR or UNKNOWN role); null when there is none. */
	static String firstAuthor(List<Contributor> contributors) {
		return contributors.stream()
			.filter(c -> c.role() == Role.AUTHOR || c.role() == Role.UNKNOWN)
			.map(Contributor::nameExact)
			.filter(name -> !name.isEmpty())
			.findFirst()
			.orElse(null);
	}

	/** Step ②'s paper ISBN from a Kyobo-style item key, else from the alt code; counted in the report. */
	static String kyoboIsbn(Row row, ImportReport report) {
		Isbn.Kyobo fromKey = Isbn.fromKyoboCode(row.itemKey());
		Isbn.Kyobo result = fromKey.result() == Isbn.KyoboResult.NOT_KYOBO_CODE ? Isbn.fromKyoboCode(row.altCode())
				: fromKey;
		report.kyoboCodes.merge(result.result().name(), 1L, Long::sum);
		return result.isbn13();
	}

}
