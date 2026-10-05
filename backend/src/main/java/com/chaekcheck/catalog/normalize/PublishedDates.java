package com.chaekcheck.catalog.normalize;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Publication dates (research search-and-normalization 4.4). */
public final class PublishedDates {

	private static final Pattern DATE = Pattern.compile("^(\\d{4})-?(\\d{2})-?(\\d{2})$");

	private PublishedDates() {
	}

	/**
	 * Reads {@code YYYY-MM-DD} or {@code YYYYMMDD}; anything else, a day that does not exist ({@code 2045-07-00}) and
	 * a date after {@code today} give null. Future dates are errors [확정].
	 */
	public static LocalDate parse(String raw, LocalDate today) {
		if (raw == null) {
			return null;
		}
		Matcher m = DATE.matcher(raw.strip());
		if (!m.matches()) {
			return null;
		}
		try {
			LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
					Integer.parseInt(m.group(3)));
			return date.isAfter(today) ? null : date;
		}
		catch (DateTimeException ex) {
			return null;
		}
	}

	/**
	 * The book's date: the date seen most often among its holdings, the earlier one on a tie [안]. Nulls (future or
	 * unreadable dates) are not counted [확정]. Null when no holding has a date.
	 */
	public static LocalDate representative(Collection<LocalDate> dates) {
		return representative(dates.stream()
			.filter(Objects::nonNull)
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting())));
	}

	/** {@link #representative(Collection)} from the number of holdings per date (no nulls). */
	public static LocalDate representative(Map<LocalDate, Long> counts) {
		return counts.entrySet()
			.stream()
			.min(Comparator.<Map.Entry<LocalDate, Long>>comparingLong(e -> -e.getValue())
				.thenComparing(Map.Entry::getKey))
			.map(Map.Entry::getKey)
			.orElse(null);
	}

}
