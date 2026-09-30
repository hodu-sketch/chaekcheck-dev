package com.chaekcheck.availability.internal;

/** The search text is shorter than 2 or longer than 50 characters after normalization ({@code invalid_query}). */
public class InvalidQueryException extends RuntimeException {

	public InvalidQueryException(int normalizedLength) {
		super("Search text must be " + SearchQuery.MIN_LENGTH + " to " + SearchQuery.MAX_LENGTH
				+ " characters after trimming, got " + normalizedLength);
	}

}
