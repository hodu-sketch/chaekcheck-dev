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

	/**
	 * The search asks the user back instead of answering (user 2026-10-26, {@code pick.ask-back}): the pick call's ask, or
	 * the fixed ask for a question that is not a book request.
	 */
	public static final String ASK = "ask";

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

	/**
	 * @param result {@code ok}, {@code no_match}, {@code no_text}, {@code partial}, or {@code site}: the head answered a
	 * question about 책첵 and no book comes (ML first call, user 2026-10-26), or {@code asked}: the agent asked back
	 * ({@code ask} event) and no book comes
	 */
	public record Done(String result, List<Long> bookIds) {

		public Done {
			bookIds = List.copyOf(bookIds);
		}

	}

	public record Error(String code) {
	}

	/** @param choices 2 to 4 short answers, shown as buttons; a click asks the choice as the next question */
	public record Ask(String question, List<String> choices) {

		public Ask {
			choices = List.copyOf(choices);
		}

	}

}
