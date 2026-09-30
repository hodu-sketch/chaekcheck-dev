package com.chaekcheck.availability.internal;

import java.util.Objects;

/**
 * What to search: a field and a normalized text (trimmed, runs of whitespace collapsed to one space,
 * 2 to 50 characters). Both are part of the cache key.
 */
public record SearchQuery(String value, SearchField field) {

	public static final int MIN_LENGTH = 2;

	public static final int MAX_LENGTH = 50;

	public SearchQuery {
		Objects.requireNonNull(field, "field");
		int length = value.codePointCount(0, value.length());
		if (length < MIN_LENGTH || length > MAX_LENGTH) {
			throw new InvalidQueryException(length);
		}
	}

	/** A title search. @throws InvalidQueryException if the normalized text is not 2 to 50 characters */
	public static SearchQuery of(String raw) {
		return of(raw, SearchField.TITLE);
	}

	/** @throws InvalidQueryException if the normalized text is not 2 to 50 characters */
	public static SearchQuery of(String raw, SearchField field) {
		String normalized = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
		return new SearchQuery(normalized, field);
	}

}
