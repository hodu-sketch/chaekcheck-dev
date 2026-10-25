package com.chaekcheck.aisearch.internal.firstcall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A first call model of 3 dimensions for the tests (the real one has 1,024): the hidden layer is the vector itself
 * (ReLU), so {@link #SITE} goes the {@code site} way, {@link #BOOK} the {@code book} way and asks for the shelf 과학,
 * {@link #NOT_BOOK} the {@code not_book} way. Book 2 is on the shelf 과학, book 5 on 소설.
 */
public final class TinyFirstCall {

	public static final float[] SITE = { 1, 0, 0 };

	public static final float[] BOOK = { 0, 1, 0 };

	public static final float[] NOT_BOOK = { 0, 0, 1 };

	private TinyFirstCall() {
	}

	public static MlFirstCall create() {
		return new MlFirstCall(model(), new BookProfiles(Map.of(2L, profile("과학"), 5L, profile("소설"))), 0.004);
	}

	static BookProfiles.Profile profile(String field) {
		return new BookProfiles.Profile(field, "", null, List.of(), "", "", "", List.of());
	}

	static FirstCallModel model() {
		List<FirstCallModel.Head> heads = new ArrayList<>();
		// not_book, title_like, refers_shown, site, book: SITE -> site, BOOK -> book, NOT_BOOK -> not_book
		heads.add(head("route", FirstCallModel.Kind.ROUTE, List.of("not_book", "title_like", "refers_shown", "site", "book"),
				new float[] { 0, 0, 5, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 5, 0 }, new float[5], 0.5, 1));
		heads.add(head("field", FirstCallModel.Kind.MULTI, List.of("과학", "소설"), new float[] { 0, 5, 0, 0, -5, 0 },
				new float[] { -1, -1 }, 1, 0.5));
		for (String name : List.of("subfield", "genre", "form", "ex_field", "ex_subfield", "ex_genre")) {
			heads.add(head(name, FirstCallModel.Kind.MULTI, List.of("x"), new float[3], new float[] { -5 }, 1, 0.5));
		}
		heads.add(head("fiction", FirstCallModel.Kind.SINGLE, List.of("True", "False", "None"), new float[9],
				new float[] { 0, 0, 5 }, 1, 1));
		for (String name : List.of("country", "audience", "level")) {
			heads.add(head(name, FirstCallModel.Kind.SINGLE, List.of("", "a"), new float[6], new float[] { 5, 0 }, 1, 1));
		}
		return new FirstCallModel(3, 3, heads);
	}

	private static FirstCallModel.Head head(String name, FirstCallModel.Kind kind, List<String> labels, float[] w2,
			float[] b2, double tau, double threshold) {
		float[] identity = { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
		return new FirstCallModel.Head(name, kind, labels, tau, threshold, 2, identity, new float[3], w2, b2);
	}

}
