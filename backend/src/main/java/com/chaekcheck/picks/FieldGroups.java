package com.chaekcheck.picks;

import java.util.List;
import java.util.Map;

/**
 * Six field groups over the recommended lists' own fields (user 2026-10-25: "처음에는 다양한 분야의 책을 골고루 섞어줘";
 * 6묶음 문학, 인문·역사, 사회·경제, 과학·기술, 예술, 그 밖). Each list names its fields differently; this maps
 * {@code (list key, field code)} to one group. The Nobel list is literature as a whole.
 */
public final class FieldGroups {

	public static final String LITERATURE = "literature";

	public static final String HUMANITIES = "humanities";

	public static final String SOCIETY = "society";

	public static final String SCIENCE = "science";

	public static final String ARTS = "arts";

	public static final String OTHER = "other";

	/** In this order on the screen when mixing starts. */
	public static final List<String> ALL = List.of(LITERATURE, HUMANITIES, SOCIETY, SCIENCE, ARTS, OTHER);

	private static final Map<String, String> BY_FIELD = Map.ofEntries(
			Map.entry("kaist:literature", LITERATURE), Map.entry("kaist:humanities", HUMANITIES),
			Map.entry("kaist:society", SOCIETY), Map.entry("kaist:science", SCIENCE),
			Map.entry("kaist:technology", SCIENCE), Map.entry("kaist:arts", ARTS),
			Map.entry("leedongjin:korean_fiction", LITERATURE), Map.entry("leedongjin:foreign_fiction", LITERATURE),
			Map.entry("leedongjin:graphic_novel", LITERATURE), Map.entry("leedongjin:essay", LITERATURE),
			Map.entry("leedongjin:humanities", HUMANITIES), Map.entry("leedongjin:history", HUMANITIES),
			Map.entry("leedongjin:society", SOCIETY), Map.entry("leedongjin:economy", SOCIETY),
			Map.entry("leedongjin:science", SCIENCE),
			Map.entry("librarian:11", LITERATURE), Map.entry("librarian:6", HUMANITIES),
			Map.entry("librarian:5", SOCIETY), Map.entry("librarian:4", SCIENCE), Map.entry("librarian:425", OTHER),
			Map.entry("snu100:korean_lit", LITERATURE), Map.entry("snu100:foreign_lit", LITERATURE),
			Map.entry("snu100:eastern_thought", HUMANITIES), Map.entry("snu100:western_thought", HUMANITIES),
			Map.entry("snu100:science_tech", SCIENCE));

	private FieldGroups() {
	}

	/** The group of a list's field, or null when the field is not known (a new field: {@link #OTHER} on the screen). */
	public static String of(String listKey, String fieldCode) {
		if ("nobel".equals(listKey)) {
			return LITERATURE;
		}
		return BY_FIELD.get(listKey + ":" + fieldCode);
	}

}
