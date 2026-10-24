package com.chaekcheck.aisearch.internal.flow;

import java.util.List;

/**
 * The stream events' data (design-delta "API", event names kebab-case, ADR-0153). The card's {@code coverUrl} is the
 * listed book's cover path on this site (the name of design-delta; {@code ListedBook.coverPath}).
 */
public final class AiSearchEvents {

	public static final String TITLE_GUESS = "title-guess";

	public static final String HEAD_DELTA = "head-delta";

	public static final String CARD = "card";

	public static final String CARD_DELTA = "card-delta";

	public static final String DONE = "done";

	public static final String ERROR = "error";

	private AiSearchEvents() {
	}

	public record TitleGuess(String title) {
	}

	public record Text(String text) {
	}

	/**
	 * @param no the card's place in the answer, from 1
	 * @param aiSummary the recommended lists' AI summary of the book, shown in the book window the card opens (user
	 * 2026-10-24), or null
	 */
	public record Card(int no, long bookId, String title, String author, String coverUrl, int myLibraryCount,
			String aiSummary) {
	}

	public record CardDelta(long bookId, String text) {
	}

	/** @param result {@code ok}, {@code no_match}, {@code no_text} or {@code partial} */
	public record Done(String result, List<Long> bookIds) {

		public Done {
			bookIds = List.copyOf(bookIds);
		}

	}

	public record Error(String code) {
	}

}
