package com.chaekcheck.aisearch.internal.firstcall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * The ML first call (user 2026-10-26 "1차를 5갈래 MLP로 바꿔요(M4 방식)", "이어 묻는 질문도 ML로 하기 → 넣음";
 * docs/reports/ai-search-ml-first-call-decisions-2026-10-26.md): from the question vector, the way (5 ways, 94.6% on
 * the 600 test questions) and the asked shelves (MLP heads, F1 close to Haiku's but for the genre); the keywords are the
 * question's own words. A follow-up question takes the earlier question's words, joined vector and shelves, its own
 * shelves put over them (M4), unless it names a big shelf the earlier one does not (a new topic: the earlier question
 * is dropped). Loaded only with {@code first-call.mode: ml} and the vectors on; any file missing or failing leaves it
 * off with one warning and the searches ask Haiku.
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
	public record Plan(Route route, List<String> keywords, boolean joinedVector, boolean newTopic, Shelves shelves) {

		public Plan {
			keywords = List.copyOf(keywords);
		}

	}

	private final FirstCallModel model;

	private final BookProfiles profiles;

	private final double shelfBonus;

	@Autowired
	public MlFirstCall(AiSearchProperties properties, JsonMapper json) {
		FirstCallModel model = null;
		BookProfiles profiles = null;
		if (properties.firstCall().mode() == AiSearchProperties.FirstCallMode.ML) {
			try {
				if (!properties.vector().enabled()) {
					throw new IllegalStateException("the question vectors are off");
				}
				Path dir = Path.of(properties.vector().modelDir());
				for (String name : List.of(FirstCallModel.NAME, BookProfiles.NAME)) {
					if (!Files.isRegularFile(dir.resolve(name))) {
						throw new IllegalStateException("missing " + name + " in " + dir);
					}
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
				profiles = BookProfiles.read(dir.resolve(BookProfiles.NAME));
				log.info("ai search first call: ml, {} book profiles", profiles.count());
			}
			catch (Exception e) {
				log.warn("ai search first call: haiku, the ml one is off: {}",
						e.getMessage() == null ? e.getClass().getName() : e.getMessage());
				model = null;
				profiles = null;
			}
		}
		this.model = model;
		this.profiles = profiles;
		this.shelfBonus = properties.firstCall().shelfBonus();
	}

	MlFirstCall(FirstCallModel model, BookProfiles profiles, double shelfBonus) {
		this.model = model;
		this.profiles = profiles;
		this.shelfBonus = shelfBonus;
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
	 */
	public Plan plan(String question, float[] vector, String earlierQuestion, float[] earlierVector) {
		Route route = Route.valueOf(model.route(vector).toUpperCase(Locale.ROOT));
		Shelves own = shelves(vector);
		if (earlierQuestion == null || earlierVector == null) {
			return new Plan(route, QuestionWords.of(question), false, false, own);
		}
		Shelves earlier = shelves(earlierVector);
		if (own.newTopicAfter(earlier)) {
			return new Plan(route, QuestionWords.of(question), false, true, own);
		}
		LinkedHashSet<String> words = new LinkedHashSet<>(QuestionWords.of(question));
		words.addAll(QuestionWords.of(earlierQuestion));
		return new Plan(route, List.copyOf(words), true, false, own.over(earlier));
	}

	/** What the book's shared shelves add to its fusion sum. */
	public double bonus(long bookId, Shelves shelves) {
		return shelfBonus * shelves.matches(profiles.of(bookId));
	}

	/** Whether the question rules the book out (it goes after the others). */
	public boolean rulesOut(long bookId, Shelves shelves) {
		return shelves.excludes(profiles.of(bookId));
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
