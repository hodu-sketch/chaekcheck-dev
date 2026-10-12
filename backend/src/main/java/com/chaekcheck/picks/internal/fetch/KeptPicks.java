package com.chaekcheck.picks.internal.fetch;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.chaekcheck.picks.internal.PickCategory;

/**
 * What a fetch keeps of the received list (spec R1-1, R2-5) [확정]: the books of the latest six distinct
 * recommendation months (all of them when there are fewer months). Field codes {@link PickCategory} does not know are
 * kept and named in {@code new_category_codes} (ADR-0089).
 * @param rows the kept books, in answer order
 * @param receivedCount rows received
 * @param missingIsbnCount kept books without an ISBN-13
 * @param newCategoryCodes unknown field codes of the kept books, comma separated in code order, or null
 */
public record KeptPicks(List<PickRow> rows, int receivedCount, int missingIsbnCount, String newCategoryCodes) {

	/** Recommendation months kept [확정 6]. */
	public static final int MONTHS = 6;

	/** Numbers by value, then other codes as text, so 425 comes after 99 [안]. */
	public static final Comparator<String> CODE_ORDER = Comparator
		.comparing((String code) -> !code.matches("[0-9]{1,18}"))
		.thenComparing(code -> code.matches("[0-9]{1,18}") ? Long.parseLong(code) : 0L)
		.thenComparing(Comparator.naturalOrder());

	/** @throws FetchFailure {@code no_books} when nothing is left */
	public static KeptPicks of(List<PickRow> received) {
		Set<LocalDate> months = new TreeSet<>(Comparator.reverseOrder());
		received.forEach(row -> months.add(row.recomMonth()));
		Set<LocalDate> kept = Set.copyOf(months.stream().limit(MONTHS).toList());
		List<PickRow> rows = received.stream().filter(row -> kept.contains(row.recomMonth())).toList();
		if (rows.isEmpty()) {
			throw new FetchFailure(FailureReason.NO_BOOKS, "received " + received.size());
		}
		Set<String> newCodes = new TreeSet<>(CODE_ORDER);
		rows.stream()
			.map(PickRow::drCode)
			.filter(code -> PickCategory.byFieldCode(code).isEmpty())
			.forEach(newCodes::add);
		int missingIsbn = (int) rows.stream().filter(row -> row.isbn13() == null).count();
		return new KeptPicks(rows, received.size(), missingIsbn, newCodes.isEmpty() ? null : String.join(",", newCodes));
	}

}
