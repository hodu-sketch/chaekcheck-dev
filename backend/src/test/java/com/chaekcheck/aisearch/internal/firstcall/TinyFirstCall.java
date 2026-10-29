package com.chaekcheck.aisearch.internal.firstcall;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

	/** Writes {@link #model()} as a {@code first-call-model.bin} the way the export does. */
	static void write(Path file) throws IOException {
		FirstCallModel model = model();
		StringBuilder header = new StringBuilder("{\"dim\":3,\"hidden\":3,\"heads\":[");
		List<FirstCallModel.Head> heads = new ArrayList<>();
		for (String name : NAMES) {
			FirstCallModel.Head head = model.head(name);
			heads.add(head);
			header.append(heads.size() > 1 ? "," : "")
				.append("{\"name\":\"").append(name)
				.append("\",\"kind\":\"").append(head.kind().name().toLowerCase(java.util.Locale.ROOT))
				.append("\",\"labels\":[")
				.append(String.join(",", head.labels().stream().map(l -> "\"" + l + "\"").toList()))
				.append("],\"tau\":").append(head.tau())
				.append(",\"threshold\":").append(head.threshold())
				.append(",\"max\":").append(head.max()).append('}');
		}
		byte[] json = header.append("]}").toString().getBytes(StandardCharsets.UTF_8);
		int floats = 0;
		for (FirstCallModel.Head head : heads) {
			floats += head.w1().length + head.b1().length + head.w2().length + head.b2().length;
		}
		ByteBuffer out = ByteBuffer.allocate(6 + 4 + json.length + floats * 4).order(ByteOrder.LITTLE_ENDIAN);
		out.put("CCFC1\n".getBytes(StandardCharsets.US_ASCII)).putInt(json.length).put(json);
		for (FirstCallModel.Head head : heads) {
			for (float[] blob : List.of(head.w1(), head.b1(), head.w2(), head.b2())) {
				for (float f : blob) {
					out.putFloat(f);
				}
			}
		}
		Files.write(file, out.array());
	}

	private static final List<String> NAMES = List.of("route", "field", "subfield", "genre", "form", "ex_field",
			"ex_subfield", "ex_genre", "fiction", "country", "audience", "level");

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
