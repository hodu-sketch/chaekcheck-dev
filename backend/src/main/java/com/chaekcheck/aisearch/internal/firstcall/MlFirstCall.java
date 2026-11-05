package com.chaekcheck.aisearch.internal.firstcall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * The ML first call (user 2026-10-26 "1차를 5갈래 MLP로 바꿔요(M4 방식)", "이어 묻는 질문도 ML로 하기 → 넣음";
 * docs/features/aisearch/reports/2026-10-26-ml-first-call-decisions.md): from the question vector, the way (5 ways, 94.6% on
 * the 600 test questions) and the asked shelves (MLP heads, F1 close to Haiku's but for the genre); the keywords are the
 * question's own words. A follow-up question takes the earlier question's words, joined vector and shelves, its own
 * shelves put over them (M4), unless it names a big shelf the earlier one does not (a new topic: the earlier question
 * is dropped). Loaded only with {@code first-call.mode: ml} and the vectors on; a model file missing or failing, or
 * {@code catalog.book_profile} empty, leaves it off with one warning and the searches ask Haiku.
 */
@Component
public class MlFirstCall {

	private static final Logger log = LoggerFactory.getLogger(MlFirstCall.class);

	/** The way a question goes; the model's labels in this order. */
	public enum Route {

		/** Not a book question: no search. */
		NOT_BOOK,
		/** A title or an author alone: the title line, then the usual search. */
		TITLE_LIKE,
		/** Points at the books shown before: those and their neighbours. */
		REFERS_SHOWN,
		/** About 책첵 or using the e-libraries: answered from the site's own text. */
		SITE,
		/** A book question. */
		BOOK

	}

	/**
	 * @param keywords the words to match, the question's first
	 * @param joinedVector whether the search vector is of the earlier question and this one joined (a follow-up that is
	 * not a new topic)
	 * @param newTopic whether a follow-up dropped the earlier question
	 */
	public record Plan(Route route, List<String> keywords, boolean joinedVector, boolean newTopic, Shelves shelves,
			String relation) {

		public Plan {
			keywords = List.copyOf(keywords);
		}

	}

	private final FirstCallModel model;

	private final BookProfiles profiles;

	private final double shelfBonus;

	/** Whether {@link #rulesOut} pushes left-out books back (off: never). */
	private final boolean rulesOutOn;

	/** The relation network of follow-ups, or null (then the rule). */
	private final FirstCallModel followUpModel;

	private final AiSearchProperties.FollowUp followUp;

	/** The relations, the follow-up model's labels in this order. */
	static final List<String> RELATIONS = List.of("refers", "ask_one", "continue", "shift", "feedback", "new", "none");

	static final String FOLLOW_UP_NAME = "follow-up-model.bin";

	/**
	 * The profiles come from {@code catalog.book_profile} once at start (cycle 26, ADR-0165, the same time as the book
	 * vectors); while that table is empty (before the first {@code book-profiles} import) the ML first call is off and
	 * the searches ask Haiku (the model folder's {@code book-profiles.tsv} is no longer read, plan cycle 26 retro,
	 * user 2026-10-29 "지우기해줘").
	 */
	@Autowired
	public MlFirstCall(AiSearchProperties properties, JsonMapper json,
			com.chaekcheck.catalog.BookProfiles catalogProfiles) {
		FirstCallModel model = null;
		BookProfiles profiles = null;
		FirstCallModel followUpModel = null;
		if (properties.firstCall().mode() == AiSearchProperties.FirstCallMode.ML) {
			try {
				if (!properties.vector().enabled()) {
					throw new IllegalStateException("the question vectors are off");
				}
				Path dir = Path.of(properties.vector().modelDir());
				if (!Files.isRegularFile(dir.resolve(FirstCallModel.NAME))) {
					throw new IllegalStateException("missing " + FirstCallModel.NAME + " in " + dir);
				}
				model = FirstCallModel.read(dir.resolve(FirstCallModel.NAME), json);
				List<String> ways = java.util.Arrays.stream(Route.values())
					.map(way -> way.name().toLowerCase(Locale.ROOT))
					.toList();
				if (!model.head("route").labels().equals(ways)) {
					throw new IllegalStateException("the model's ways are not " + ways);
				}
				for (String name : List.of("field", "subfield", "genre", "form", "ex_field", "ex_subfield", "ex_genre",
						"fiction", "country", "audience", "level")) {
					model.head(name);
				}
				Map<Long, com.chaekcheck.catalog.BookProfiles.Profile> stored = catalogProfiles.all();
				if (stored.isEmpty()) {
					throw new IllegalStateException("catalog.book_profile is empty (run the book-profiles import)");
				}
				profiles = BookProfiles.of(stored);
				if (properties.firstCall().followUp() == AiSearchProperties.FollowUp.RELATION) {
					Path file = dir.resolve(FOLLOW_UP_NAME);
					if (Files.isRegularFile(file)) {
						followUpModel = FirstCallModel.read(file, json);
						if (!followUpModel.head("relation").labels().equals(RELATIONS)
								|| followUpModel.dim() != 4 * model.dim()) {
							throw new IllegalStateException("the follow-up model's relations or size are not as expected");
						}
					}
					else {
						log.warn("ai search first call: no {}, follow-ups by the rule", FOLLOW_UP_NAME);
					}
				}
				log.info("ai search first call: ml, {} book profiles from catalog.book_profile, follow-ups by {}", profiles.count(),
						followUpModel != null ? "relation" : properties.firstCall().followUp() == AiSearchProperties.FollowUp.STRICT ? "strict rule" : "rule");
			}
			catch (Exception e) {
				log.warn("ai search first call: haiku, the ml one is off: {}",
						e.getMessage() == null ? e.getClass().getName() : e.getMessage());
				model = null;
				profiles = null;
				followUpModel = null;
			}
		}
		this.model = model;
		this.profiles = profiles;
		this.shelfBonus = properties.firstCall().shelfBonus();
		this.rulesOutOn = properties.firstCall().rulesOut();
		this.followUpModel = followUpModel;
		this.followUp = properties.firstCall().followUp();
	}

	MlFirstCall(FirstCallModel model, BookProfiles profiles, double shelfBonus) {
		this(model, profiles, shelfBonus, null, AiSearchProperties.FollowUp.RULE);
	}

	MlFirstCall(FirstCallModel model, BookProfiles profiles, double shelfBonus, FirstCallModel followUpModel,
			AiSearchProperties.FollowUp followUp) {
		this.model = model;
		this.profiles = profiles;
		this.shelfBonus = shelfBonus;
		this.rulesOutOn = true;
		this.followUpModel = followUpModel;
		this.followUp = followUp;
	}

	/** Off: the searches ask Haiku (tests, and where the files are not mounted). */
	public static MlFirstCall off() {
		return new MlFirstCall(null, null, 0);
	}

	/** Whether the searches use it; off, they ask Haiku. */
	public boolean on() {
		return model != null;
	}

	/**
	 * @param vector the question's vector
	 * @param earlierQuestion the last earlier question, or null on the first turn
	 * @param earlierVector its vector, or null
	 * @param joinedVector the vector of the earlier question and this one joined, or null
	 */
	public Plan plan(String question, float[] vector, String earlierQuestion, float[] earlierVector,
			float[] joinedVector) {
		return plan(question, vector, earlierQuestion, earlierVector, joinedVector, false);
	}

	/** The keywords the plan of a first question (no earlier question) searches with. */
	public static List<String> firstKeywords(String question) {
		return QuestionWords.of(question);
	}

	/**
	 * @param pointsBack the question points back at several earlier questions and {@code earlierQuestion} holds them
	 * joined: whatever the relation network says, the search goes on from all of them (a continue), since a new topic
	 * or the shown books alone drop what the question asks to combine (user 2026-10-26, 「앞 주제 둘 합치기」)
	 */
	public Plan plan(String question, float[] vector, String earlierQuestion, float[] earlierVector,
			float[] joinedVector, boolean pointsBack) {
		Route route = Route.valueOf(model.route(vector).toUpperCase(Locale.ROOT));
		Shelves own = shelves(vector);
		List<String> ownWords = QuestionWords.of(question);
		if (earlierQuestion == null || earlierVector == null) {
			return new Plan(route, ownWords, false, false, own, "first");
		}
		Shelves earlier = shelves(earlierVector);
		LinkedHashSet<String> words = new LinkedHashSet<>(ownWords);
		words.addAll(QuestionWords.of(earlierQuestion));
		List<String> joinedWords = List.copyOf(words);
		if (followUpModel != null && joinedVector != null) {
			String relation = followUpModel.label("relation", features(vector, earlierVector, joinedVector));
			if (pointsBack && !relation.equals("none")) {
				return new Plan(Route.BOOK, joinedWords, true, false, own.over(earlier), relation + " joined");
			}
			return switch (relation) {
				case "refers", "ask_one" -> new Plan(Route.REFERS_SHOWN, joinedWords, true, false, own.over(earlier),
						relation);
				case "continue", "feedback" -> new Plan(Route.BOOK, joinedWords, true, false, own.over(earlier),
						relation);
				case "shift" -> new Plan(Route.BOOK, ownWords, true, false, own.keepingFieldOf(earlier), relation);
				case "new" -> new Plan(route == Route.REFERS_SHOWN ? Route.BOOK : route, ownWords, false, true, own,
						relation);
				// not a book search: the way network tells a question about 책첵, a title or other talk apart
				default -> new Plan(route == Route.BOOK || route == Route.REFERS_SHOWN ? Route.NOT_BOOK : route,
						ownWords, false, true, own, relation);
			};
		}
		boolean newTopic = own.newTopicAfter(earlier) && (followUp != AiSearchProperties.FollowUp.STRICT
				|| joinedVector == null || shelves(joinedVector).newTopicAfter(earlier));
		if (newTopic) {
			return new Plan(route, ownWords, false, true, own, "rule new");
		}
		return new Plan(route, joinedWords, true, false, own.over(earlier), "rule continue");
	}

	/** The follow-up network's input: this question's, the earlier one's, the joined vector and this x earlier. */
	static float[] features(float[] vector, float[] earlier, float[] joined) {
		int d = vector.length;
		float[] f = new float[4 * d];
		System.arraycopy(vector, 0, f, 0, d);
		System.arraycopy(earlier, 0, f, d, d);
		System.arraycopy(joined, 0, f, 2 * d, d);
		for (int i = 0; i < d; i++) {
			f[3 * d + i] = vector[i] * earlier[i];
		}
		return f;
	}

	/** What the book's shared shelves add to its fusion sum. */
	public double bonus(long bookId, Shelves shelves) {
		return shelfBonus * shelves.matches(profiles.of(bookId));
	}

	/**
	 * How many of the books have each big shelf (field, and fiction or not), most first; books without a profile are
	 * left out, and nothing when the profiles are not loaded (the ask-back's 「후보 분야」 line, user 2026-10-27).
	 */
	public LinkedHashMap<String, Integer> shelfCounts(Collection<Long> bookIds) {
		Map<String, Integer> counts = new HashMap<>();
		if (profiles != null) {
			for (long bookId : bookIds) {
				BookProfiles.Profile profile = profiles.of(bookId);
				if (profile == null || profile.field() == null || profile.field().isBlank()) {
					continue;
				}
				String shelf = profile.field()
						+ (profile.fiction() == null ? "" : profile.fiction() ? "(소설)" : "(비소설)");
				counts.merge(shelf, 1, Integer::sum);
			}
		}
		LinkedHashMap<String, Integer> sorted = new LinkedHashMap<>();
		counts.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
				.thenComparing(Map.Entry.comparingByKey()))
			.forEach(e -> sorted.put(e.getKey(), e.getValue()));
		return sorted;
	}

	/** Whether the question rules the book out (it goes after the others); never when the setting is off. */
	public boolean rulesOut(long bookId, Shelves shelves) {
		return rulesOutOn && shelves.excludes(profiles.of(bookId));
	}

	Shelves shelves(float[] v) {
		Boolean fiction = switch (model.label("fiction", v)) {
			case "True" -> Boolean.TRUE;
			case "False" -> Boolean.FALSE;
			default -> null;
		};
		return new Shelves(model.labels("field", v), model.labels("subfield", v), fiction, model.labels("genre", v),
				model.label("country", v), model.label("audience", v), model.label("level", v), model.labels("form", v),
				model.labels("ex_field", v), model.labels("ex_subfield", v), model.labels("ex_genre", v));
	}

}
