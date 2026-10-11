package com.chaekcheck.popular.internal.fetch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a category's sales count from the sales statistics answer (plan research kpipa.md 2-1절): the chart script's
 * {@code dataX1 = Number("…")}. The answer holds that line twice: first in the {@code bookType} branch (what is
 * requested, the count), then {@code Number("")} in the {@code saleType} branch. Only the first one is read; it must
 * be digits.
 */
public final class SalesCountParser {

	private static final Pattern DATA_X1 = Pattern.compile("dataX1\\s*=\\s*Number\\(\\s*\"([^\"]*)\"\\s*\\)");

	private static final Pattern DIGITS = Pattern.compile("[0-9]{1,18}");

	private SalesCountParser() {
	}

	/** @throws FetchFailure {@code format_changed} when the first {@code dataX1} is missing or not a number */
	public static long parse(String html, String list) {
		Matcher matcher = DATA_X1.matcher(html);
		if (!matcher.find()) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, list + ": no dataX1");
		}
		String value = matcher.group(1).strip();
		if (!DIGITS.matcher(value).matches()) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, list + ": dataX1 is not a number");
		}
		return Long.parseLong(value);
	}

}
