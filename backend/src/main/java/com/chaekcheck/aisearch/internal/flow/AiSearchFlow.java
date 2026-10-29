package com.chaekcheck.aisearch.internal.flow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.config.PickProperties;
import com.chaekcheck.aisearch.internal.firstcall.MlFirstCall;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Card;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.CardDelta;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Done;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Text;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.TitleGuess;
import com.chaekcheck.aisearch.internal.flow.Prompts.Choice;
import com.chaekcheck.aisearch.internal.flow.Prompts.ShownTurn;
import com.chaekcheck.aisearch.internal.llm.LlmClient;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Failure;
import com.chaekcheck.aisearch.internal.llm.LlmClient.LlmCall;
import com.chaekcheck.aisearch.internal.llm.LlmClient.LlmException;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Result;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Usage;
import com.chaekcheck.aisearch.internal.match.CandidateQuery;
import com.chaekcheck.aisearch.internal.match.CandidateQuery.Candidate;
import com.chaekcheck.aisearch.internal.match.SearchBooks;
import com.chaekcheck.aisearch.internal.match.SearchBooks.SearchBook;
import com.chaekcheck.aisearch.internal.match.SearchText;
import com.chaekcheck.aisearch.internal.quota.DailyBudget;
import com.chaekcheck.aisearch.internal.vector.QuestionVectors;
import com.chaekcheck.catalog.BookCards;
import com.chaekcheck.catalog.BookTexts;
import com.chaekcheck.foryou.BookNeighbours;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageLog.AiSearchRecord;
import com.chaekcheck.usagelog.UsageLog.KeywordResult;
import com.chaekcheck.usagelog.UsageLog.PickResult;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * One AI search after the checks (cycle 24 handoff step 8-2, design-delta "주요 흐름"): the first call turns the question
 * into keywords (or the question's own words when it fails, spec R6-1), the keyword match picks at most 30 search
 * books (the listed books and the books held by the most libraries, user 2026-10-25) the plan libraries hold, the second call streams the head, then each pick's number and text. A pick's card
 * goes out only after its number is checked (in range, not seen, at most 8; ADR-0153): a refused number sends neither
 * card nor text. A card's text stops at 300 characters. The second call failing before any card sends the first 8
 * books without text ({@code no_text}); failing after one sends {@code partial}. The day's cost and one usage row are
 * written at the end. Neither the question nor the history is logged. Titles and authors come from the search books;
 * a listed book's card adds its list cover and AI summary; a card without a list cover takes the tab cover (cycle 23's,
 * user 2026-10-25), and the other books have no AI summary yet. With {@code first-call.mode: ml} the first step asks no
 * model ({@link MlFirstCall}, user 2026-10-26): the way, the words and the shelves come from the question vector, the
 * vector candidates from the question's (or, for a follow-up, the earlier and this question's joined) vector, and the
 * shared shelves reorder the mix; a question about 책첵 is answered from the site's own text.
 */
@Component
public class AiSearchFlow {

	private static final Logger log = LoggerFactory.getLogger(AiSearchFlow.class);

	private final LlmClient llm;

	private final CandidateQuery candidates;

	private final DailyBudget budget;

	private final ListedBooks listedBooks;

	private final SearchBooks searchBooks;

	private final BookNeighbours neighbours;

	private final BookTexts bookTexts;


	private final QuestionVectors vectors;

	/** What a follow-up means by its vector (「최신」, 「다 같이」, the topics 「아까」 names), next to the rules. */
	private final IntentVectors intents;

	private final MlFirstCall mlFirstCall;

	private final BookCards bookCards;

	private final PickProperties pick;

	private final UsageLog usageLog;

	private final AiSearchProperties properties;

	private final JsonMapper json;

	AiSearchFlow(LlmClient llm, CandidateQuery candidates, DailyBudget budget, ListedBooks listedBooks,
			SearchBooks searchBooks, BookNeighbours neighbours, BookTexts bookTexts,
			QuestionVectors vectors, MlFirstCall mlFirstCall, BookCards bookCards, PickProperties pick,
			UsageLog usageLog, AiSearchProperties properties, JsonMapper json) {
		this.llm = llm;
		this.candidates = candidates;
		this.budget = budget;
		this.listedBooks = listedBooks;
		this.searchBooks = searchBooks;
		this.neighbours = neighbours;
		this.bookTexts = bookTexts;
		this.vectors = vectors;
		this.intents = new IntentVectors(vectors);
		this.mlFirstCall = mlFirstCall;
		this.bookCards = bookCards;
		this.pick = pick;
		this.usageLog = usageLog;
		this.properties = properties;
		this.json = json;
	}

	/**
	 * @param question trimmed, 2 to 200 characters
	 * @param turnNo the question's number in its conversation (1 to 20, from the web)
	 * @param history at most 9 earlier turns, oldest first (a conversation is 10 questions, user 2026-10-29)
	 * @param planLibraryIds the plan's library ids ({@code LibraryCatalog.planSearch})
	 * @param usage what the usage log took from the request
	 * @param startedNanos {@link System#nanoTime()} when the request came in
	 */
	public record Input(String question, int turnNo, List<Turn> history, Set<Long> planLibraryIds, UsageRequest usage,
			long startedNanos) {

		public Input {
			history = List.copyOf(history);
			planLibraryIds = Set.copyOf(planLibraryIds);
		}

	}

	/**
	 * An earlier question and the books its answer showed (at most 8), or the ask-back it showed instead (user
	 * 2026-10-27: a typed answer to it joins that question), and the head the answer wrote (user 2026-10-29 「대화
	 * 기억」), or null.
	 */
	public record Turn(String question, List<Long> bookIds, String askedBack, List<String> askChoices, String head) {

		public Turn {
			bookIds = List.copyOf(bookIds);
			askChoices = askChoices == null ? List.of() : List.copyOf(askChoices);
		}

		public Turn(String question, List<Long> bookIds) {
			this(question, bookIds, null, List.of(), null);
		}

		public Turn(String question, List<Long> bookIds, String askedBack, List<String> askChoices) {
			this(question, bookIds, askedBack, askChoices, null);
		}

	}

	/** What the first call said (or the fallback). */
	record KeywordPlan(boolean notBook, boolean titleLike, String title, boolean refersToShown, List<String> keywords,
			List<String> core, KeywordResult result) {
	}

	/**
	 * The lowest fit the second call may give a pick that is shown (cycle 24 1단계 [안], user 2026-10-25: 2 = the core
	 * topic fits, some of the conditions may not). A lower pick sends neither card nor text.
	 */
	static final int MIN_FIT = 2;

	/** An ask-back shows at most this many choices, each at most this long (user 2026-10-26 mock-up: 2 to 4 pills) [안]. */
	static final int MAX_ASK_CHOICES = 4;

	static final int MAX_ASK_CHOICE_CHARS = 40;

	/** Books of the title a question is that go first among the candidates, at most (user 2026-10-29 「해리포터」) [안]. */
	static final int MAX_TITLE_BOOKS = 3;

	/** A summary longer than this is not read on (4 lines of about 80 characters are asked for) [안]. */
	static final int TAIL_MAX_CHARS = 500;

	/** The {@code tail} of the whole answer, or null when it cannot be read. */
	private JsonNode tailOf(String answer) {
		try {
			return json.readTree(answer).path("tail");
		}
		catch (JacksonException e) {
			return null;
		}
	}

	/** Stops the stream once the summary runs on; the cards are out by then. */
	private static final class TailTooLong extends RuntimeException {

		TailTooLong() {
			super("the summary runs on", null, false, false);
		}

	}

	private static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");

	/** A title shorter than this (letters only) is taken as a topic, not as the book (user 2026-10-29) [안]. */
	static final int MIN_TITLE_LETTERS = 3;

	/** 「더 쉬운 거」, 「더 짧은 걸로」: more of a quality, which a book shown last may have most (user 2026-10-29). */
	static final java.util.regex.Pattern MORE_SO = java.util.regex.Pattern
		.compile("더\\s*[가-힣]+(운|은|한|는|진|적인)\\s*(거|걸|것|책|게)");

	/**
	 * A short follow-up that only adds a condition to the topic (「최신책」, 「짧은 거」, 「쉬운 걸로」): the topic goes on
	 * whatever the relation network says (user 2026-10-29: 「최신책」 after 세계사·과학사 was taken as a new topic).
	 */
	static final java.util.regex.Pattern CONDITION = java.util.regex.Pattern
		.compile("최신|신간|신작|요즘|요새|최근|새로\\s*나온|짧|얇|쉬운|쉽게|어려운|가벼운|두꺼운|만화|그림|번역|국내|외국|해외|\\d{4}\\s*년");

	/** A follow-up of at most this many words may be a condition (CONDITION). */
	static final int CONDITION_WORDS = 2;

	/** 「다 같이」, 「전부」 after an ask-back: every choice it gave (user 2026-10-29). */
	static final java.util.regex.Pattern ALL_CHOICES = java.util.regex.Pattern
		.compile("다\\s*같이|전부|모두|다\\s*(해|줘|보여|찾아|골라|요)|(셋|둘|세\\s*개|두\\s*개)\\s*다|^다$");

	/** 「전부 별로」, 「다 싫어」: every choice turned down, not taken. */
	static final java.util.regex.Pattern NOT_ALL = java.util.regex.Pattern.compile("별로|싫|말고|아니|빼고|없");

	/** How many times the vector candidates' depth the recent books of 「최신」 are taken from, nearest first. */
	static final int RECENT_WINDOW = 4;

	/** How many times the vector candidates' depth the nearest books are read, so 「최신」 has recent ones among them. */
	static final int NEAREST_POOL = 10;

	/** A question of at most this many words gets the 「후보 분야」 line with ask-back on (user 2026-10-27) [안]. */
	static final int SHORT_QUESTION_WORDS = 2;

	/**
	 * A first question of at most this many words may be asked back; a longer one goes to the call without the ask
	 * field, which the call wrote (empty) before the head (user 2026-10-27, 응답 시간 4안: the head 0.05 s later, its
	 * slowest tenth 0.6 s; the longest question the rule test saw asked back had 4 words) [안].
	 */
	static final int ASK_QUESTION_WORDS = 4;

	/** Reads that run beside the search on a virtual thread each (the early keyword search, covers and summaries). */
	private static final Executor BESIDE = task -> Thread.ofVirtual().start(task);

	/**
	 * Neighbours of the books shown before that a follow-up question pointing at them gets as candidates, at most
	 * (user 2026-10-25 "이어서 묻기에 앞 책의 이웃을 후보로", [안] 12).
	 */
	static final int MAX_NEIGHBOUR_CANDIDATES = 12;

	/**
	 * Neighbours of the book a "○○ 같은 책" question names that go first among the candidates, at most (user 2026-10-25,
	 * docs/aisearch/reports/2026-10-25-eval.md 3-3, [안] 20 as measured).
	 */
	static final int MAX_NAMED_NEIGHBOURS = 20;

	/** Thrown into the model client when the client is gone; ends the stream as {@code CANCELLED}. */
	private static final class ClientGone extends RuntimeException {

		ClientGone() {
			super("the client is gone", null, false, false);
		}

	}

	/** Everything one run counts. */
	private static final class Run {

		final AiSearchSink sink;

		volatile boolean gone;

		long inputTokens;

		long outputTokens;

		KeywordResult keywordResult = KeywordResult.OK;

		boolean titleLike;

		PickResult pickResult = PickResult.SKIPPED;

		int candidateCount;

		/** Vector candidates mixed in (0: none, off or late). */
		int vectorCount;

		/** Whether the question named a book whose neighbours went first. */
		boolean named;

		/** What the question's talk words pointed at (TalkRefs), or null (plan 2026-10-29, A–E). */
		String talk;

		/** Whether the keyword search run beside the ML step was taken (user 2026-10-27, 응답 시간 1안). */
		boolean earlyKeywords;

		/** The ML first call's way, or null when Haiku made the first step. */
		MlFirstCall.Route mlRoute;

		/** Whether the ML first call took a follow-up as a new topic. */
		boolean newTopic;

		/** The ML first call's relation of a follow-up to the earlier question ("first" on the first turn). */
		String relation;

		/** Whether the summary was left out (its book numbers), or null (user 2026-10-29). */
		String tail;

		/** How the ask-back went (single-ask, not-book-ask, one-word), or null (user 2026-10-26). */
		String ask;

		/** The first year of 「최신」, or null (user 2026-10-29). */
		Integer fromYear;

		/** How many topics the keywords were searched for one by one (「A나 B」, 「다 같이」), 0 for one search. */
		int parts;

		/**
		 * The letters the pick call wrote in the cards' texts, the ones past the card limit too, and the cards whose text
		 * ran past it (production v1.22.1: one turn of 14.3 s wrote 3,186 tokens, three times the usual, and nothing said
		 * where they went).
		 */
		int textChars;

		int longCards;

		final List<Long> shown = new ArrayList<>();

		/** When each step ended, ms from the request's start, for the log line (user 2026-10-26 「각 로직들 하나하나」). */
		final StringBuilder steps = new StringBuilder();

		final long startedNanos;

		Run(AiSearchSink sink, long startedNanos) {
			this.sink = sink;
			this.startedNanos = startedNanos;
		}

		void mark(String step) {
			steps.append(steps.isEmpty() ? "" : " ").append(step).append('=')
				.append((System.nanoTime() - startedNanos) / 1_000_000);
		}

		boolean send(String event, Object data) {
			if (gone || sink.gone()) {
				gone = true;
				return false;
			}
			if (!sink.send(event, data)) {
				gone = true;
			}
			return !gone;
		}

	}

	public void run(Input input, AiSearchSink sink) {
		Run run = new Run(sink, input.startedNanos());
		try {
			answer(input, run);
		}
		catch (RuntimeException e) {
			log.error("ai search failed: {}", e.getClass().getName());
			run.send(AiSearchEvents.ERROR, new AiSearchEvents.Error("internal_error"));
		}
		finally {
			try {
				int elapsedMs = (int) Math.min(Integer.MAX_VALUE,
						Duration.ofNanos(System.nanoTime() - input.startedNanos()).toMillis());
				usageLog.recordAiSearch(input.usage(),
						new AiSearchRecord(elapsedMs, input.turnNo(), run.keywordResult, run.titleLike, run.pickResult,
								run.candidateCount, run.shown, (int) Math.min(Integer.MAX_VALUE, run.inputTokens),
								(int) Math.min(Integer.MAX_VALUE, run.outputTokens)));
				log.info("ai search: first {}, keyword {}, pick {}, candidates {} (vector {}{}), cards {}, {} ms, tokens {}/{}{}",
							run.mlRoute == null ? "haiku" : "ml " + run.mlRoute + " " + run.relation,
						run.keywordResult, run.pickResult, run.candidateCount, run.vectorCount,
						(run.named ? ", named book" : "") + (run.earlyKeywords ? ", early keyword" : "")
								+ (run.talk == null ? "" : ", talk " + run.talk), run.shown.size(), elapsedMs, run.inputTokens, run.outputTokens,
						(run.ask == null ? "" : ", ask " + run.ask) + (run.tail == null ? "" : ", tail " + run.tail)
								+ (run.fromYear == null ? "" : ", from " + run.fromYear)
								+ (run.parts == 0 ? "" : ", parts " + run.parts)
								+ (run.textChars == 0 ? "" : ", text " + run.textChars
										+ (run.longCards == 0 ? "" : " (long " + run.longCards + ")"))
								+ (run.gone ? ", client gone" : "")
								+ (run.steps.isEmpty() ? "" : ", steps " + run.steps));
			}
			catch (RuntimeException e) {
				log.warn("ai search: the usage row was not kept: {}", e.getClass().getName());
			}
			finally {
				sink.complete();
			}
		}
	}

	private void answer(Input input, Run run) {
		AiSearchProperties.Vector vectorSettings = properties.vector();
		// words that point at the dialogue (「모르겠어」, 「그거 말고」, 「아까 이야기한 거」): the search goes on from the topic
		// they point at, not from their own words (plan 2026-10-29, A–E)
		Talk talk = talk(input, this::backTopics);
		if (talk != null) {
			run.talk = talk.kind() + " " + talk.mode();
		}
		String question = talk != null ? talk.topic() : input.question();
		String earlierQuestion = talk != null || input.history().isEmpty() ? null
				: EarlierTopics.of(input.question(), input.history().stream().map(Turn::question).toList());
		// a question after an ask-back is its answer, typed or a choice: it always joins the asked question (user
		// 2026-10-27), as a question pointing back at earlier topics does
		boolean answersAsk = !input.history().isEmpty() && input.history().getLast().askedBack() != null;
		boolean pointsBack = earlierQuestion != null
				&& (answersAsk || !earlierQuestion.equals(input.history().getLast().question()));
		// 「다 같이」 after an ask-back: the choices it gave are what is searched, each on its own (user 2026-10-29: 「아니 왜
		// 못 골라 다 같이 하면 되자나」 was searched as it is and found one book)
		List<String> askChoices = answersAsk && input.history().getLast().askChoices() != null
				? input.history().getLast().askChoices() : List.of();
		// not after the fixed ask of a question that is no book question: its choices are examples, not topics
		boolean allChoices = talk == null && !askChoices.isEmpty()
				&& !Prompts.NOT_BOOK_ASK.equals(input.history().getLast().askedBack()) && allChoices(input.question());
		String searchQuestion = allChoices ? String.join(" ", askChoices) : question;
		// a short follow-up adding a condition goes on with the topic (CONDITION)
		boolean condition = talk == null && !input.history().isEmpty() && !answersAsk
				&& words(input.question()) <= CONDITION_WORDS && CONDITION.matcher(input.question()).find();
		// a first question's keyword search needs only its words: it runs while the vectors and the ML step do, and is
		// taken when the plan searches with the same words and leaves nothing out (user 2026-10-27, 응답 시간 1안)
		List<String> earlyWords = input.history().isEmpty() ? MlFirstCall.firstKeywords(input.question()) : List.of();
		int keywordLimit = Math.max(vectorSettings.keywordDepth(), properties.limits().candidates());
		CompletableFuture<List<Candidate>> earlyKeywords = earlyWords.isEmpty() ? null
				: beside(() -> candidates.find(earlyWords, List.of(), input.planLibraryIds(), Set.of(), keywordLimit));
		MlStep mlStep = properties.firstCall().mode() == AiSearchProperties.FirstCallMode.ML && mlFirstCall.on()
				&& vectors.on() ? mlStep(searchQuestion, earlierQuestion, pointsBack || condition,
						condition && onlyRecency(input.question()), vectorSettings, run) : null;
		// the question vector is made while the first call runs; only the question goes in, never the history (the ML
		// first step above sends the earlier question too)
		CompletableFuture<List<Long>> nearest = mlStep != null ? CompletableFuture.completedFuture(mlStep.nearest())
				: vectors.on() ? vectors.nearest(searchQuestion, vectorSettings.vectorDepth() * NEAREST_POOL) : null;
		Map<Long, SearchBook> known = searchBooks.byId();
		Map<Long, ListedBook> listed = new HashMap<>();
		for (ListedBook book : listedBooks.current().books()) {
			listed.put(book.bookId(), book);
		}
		List<ShownTurn> history = new ArrayList<>();
		Set<Long> shownBefore = new LinkedHashSet<>();
		List<Long> lastShown = List.of();
		for (Turn turn : input.history()) {
			List<Long> shown = turn.bookIds()
				.stream()
				.filter(known::containsKey)
				.distinct()
				.limit(properties.limits().picks())
				.toList();
			// with the author, so 「작가가 누구야?」 and 「두 번째 책」 have it (user 2026-10-29: a wrong author)
			history.add(new ShownTurn(turn.question(), shown.stream().map(id -> shownLine(known.get(id))).toList(),
					turn.askedBack(), turn.askChoices(), turn.head()));
			shownBefore.addAll(shown);
			lastShown = shown;
		}

		KeywordPlan plan;
		if (mlStep != null) {
			MlFirstCall.Plan ml = mlStep.plan();
			run.mlRoute = ml.route();
			run.newTopic = ml.newTopic();
			run.relation = ml.relation();
			if (ml.route() == MlFirstCall.Route.SITE && talk == null) {
				site(input, history, run);
				return;
			}
			// not a book request, one word too (「안녕」, 「고마워」, 「엄마」): the chat call answers it and leads back to books
			// (user 2026-10-29; a one-word topic was searched as a book topic before, 「「고마워」와 관련해서…」)
			plan = new KeywordPlan(ml.route() == MlFirstCall.Route.NOT_BOOK && talk == null,
					ml.route() == MlFirstCall.Route.TITLE_LIKE,
					ml.route() == MlFirstCall.Route.TITLE_LIKE ? input.question() : "",
					ml.route() == MlFirstCall.Route.REFERS_SHOWN, ml.keywords(), List.of(), KeywordResult.OK);
		}
		else {
			plan = keywords(searchQuestion, history, run);
			if (talk != null) {
				plan = new KeywordPlan(false, false, "", false, plan.keywords(), plan.core(), plan.result());
			}
		}
		run.keywordResult = plan.result();
		// 「최신」 in the question or carried from the topic it goes on with: recent books are searched first (user
		// 2026-10-29), and the recency word itself is no search word
		boolean carries = !input.history().isEmpty() && (talk != null || mlStep == null || !mlStep.plan().newTopic());
		List<String> recencyTexts = new ArrayList<>(List.of(searchQuestion));
		if (carries) {
			input.history().reversed().forEach(turn -> recencyTexts.add(turn.question()));
		}
		Integer fromYear = fromYear(recencyTexts, searchQuestion, mlStep == null ? null : mlStep.vector());
		run.fromYear = fromYear;
		List<String> parts = allChoices ? askChoices : TopicParts.of(searchQuestion);
		run.titleLike = plan.titleLike();
		String titleGuess = null;
		if (plan.titleLike() && !plan.title().isBlank()) {
			String title = plan.title().strip();
			// only words of the question go into the button, else the whole question (ADR-0153)
			String guess = input.question().contains(title) ? title : input.question();
			if (TitleMatch.close(guess, searchBooks.books())) {
				run.send(AiSearchEvents.TITLE_GUESS, new TitleGuess(WholeText.of(guess)));
				titleGuess = guess;
			}
		}
		if (plan.notBook()) {
			if (pick.askBack()) {
				chat(input, history, run);
				return;
			}
			finish(run, PickResult.NO_MATCH, "no_match");
			return;
		}

		boolean refers = talk == null && plan.refersToShown() && !lastShown.isEmpty();
		Map<Long, Integer> planCount = new HashMap<>();
		int max = properties.limits().candidates();
		LinkedHashSet<Long> chosen = new LinkedHashSet<>();
		if (refers) {
			// the plan library counts of the books shown last and of their neighbours, in one query
			Map<Long, List<Long>> found = neighbours.of(lastShown);
			Set<Long> asked = new LinkedHashSet<>(lastShown);
			found.values().forEach(asked::addAll);
			// the books of the turns before the last one too, after it: "위에 말한 두 개" can point at two earlier
			// questions, and the last turn alone held only one of them (user 2026-10-26)
			List<Long> earlierShown = new ArrayList<>(shownBefore);
			earlierShown.removeAll(lastShown);
			asked.addAll(earlierShown);
			Map<Long, Integer> counts = candidates.planCounts(asked, input.planLibraryIds());
			lastShown.stream().filter(counts::containsKey).limit(max).forEach(id -> {
				chosen.add(id);
				planCount.put(id, counts.get(id));
			});
			earlierShown.stream().filter(counts::containsKey).limit(Math.max(0, max - chosen.size())).forEach(id -> {
				chosen.add(id);
				planCount.put(id, counts.get(id));
			});
			for (long bookId : nearby(lastShown, found, shownBefore,
					id -> counts.containsKey(id) && known.containsKey(id))) {
				if (chosen.size() >= max) {
					break;
				}
				chosen.add(bookId);
				planCount.put(bookId, counts.get(bookId));
			}
		}
		Set<String> titles = new HashSet<>();
		if (talk != null && talk.excludeShown()) {
			// 「그거 말고」 after cards: another edition of a shown title is the same book to the user (plan 2026-10-29)
			shownBefore.stream().map(known::get).filter(java.util.Objects::nonNull).forEach(b -> titles.add(b.titleKey()));
		}
		if (!refers) {
			NamedBook named = NamedBook.find(input.question(), searchBooks.books());
			if (named != null) {
				run.named = true;
				List<Long> near = namedNeighbours(named, known, shownBefore);
				Map<Long, Integer> counts = candidates.planCounts(near, input.planLibraryIds());
				for (long bookId : near) {
					if (chosen.size() < max && counts.containsKey(bookId) && titles.add(known.get(bookId).titleKey())) {
						chosen.add(bookId);
						planCount.put(bookId, counts.get(bookId));
					}
				}
			}
		}
		// a question that is a book's title (「해리포터」, 「채식주의자」): that book goes first, then books like it, never an
		// ask-back (user 2026-10-29: it asked back with critiques, English readers and fantasy). Only a title of at least
		// MIN_TITLE_LETTERS letters held by the plan libraries: a short word (「바다」, 「커피」, 「그림」) is a topic first, and
		// a title-first answer there lost the ask-back the rule test wants (ask_yes 15/16 → 7/16)
		String titleNote = null;
		if (!refers && talk == null && titleGuess != null && SearchText.letters(titleGuess).length() >= MIN_TITLE_LETTERS) {
			List<Long> titled = NamedBook.titled(titleGuess, searchBooks.books(), MAX_TITLE_BOOKS);
			Map<Long, Integer> counts = candidates.planCounts(titled, input.planLibraryIds());
			List<Long> first = new ArrayList<>();
			for (long bookId : titled) {
				if (counts.containsKey(bookId) && known.containsKey(bookId) && titles.add(known.get(bookId).titleKey())) {
					first.add(bookId);
					planCount.put(bookId, counts.get(bookId));
				}
			}
			if (!first.isEmpty()) {
				LinkedHashSet<Long> reordered = new LinkedHashSet<>(first);
				reordered.addAll(chosen);
				chosen.clear();
				chosen.addAll(reordered);
				titleNote = "사용자가 쓴 「" + titleGuess + "」는 책 제목이다. 그 제목의 책을 먼저 고르고, 이어서 비슷한 책을 고른다. "
						+ "되묻지 않는다. 머리말에서 그 책을 찾았다고 말한다.";
			}
		}
		// a question combining earlier topics may get the books shown for them again (EarlierTopics), and so may one
		// adding a condition (continue: 「수학적인 것도 좀」 narrows the list, and a shown book may meet it; user
		// 2026-10-26); 「다른 거」 (feedback) and new topics still leave them out
		run.mark("neighbours");
		boolean narrows = mlStep != null && "continue".equals(mlStep.plan().relation());
		if (narrows && talk == null && !refers && MORE_SO.matcher(input.question()).find()) {
			// 「더 쉬운 거」: the books shown last are candidates too, so the easiest of them can answer (user 2026-10-29,
			// it found none before); not 「더 보여줘」, which wants other books
			Map<Long, Integer> counts = candidates.planCounts(lastShown, input.planLibraryIds());
			for (long bookId : lastShown) {
				if (chosen.size() < max && counts.containsKey(bookId) && titles.add(known.get(bookId).titleKey())) {
					chosen.add(bookId);
					planCount.put(bookId, counts.get(bookId));
				}
			}
		}
		Set<Long> excluded = new HashSet<>(talk != null ? talk.excludeShown() ? shownBefore : Set.of()
				: refers || pointsBack || narrows ? Set.of() : shownBefore);
		excluded.addAll(chosen);
		boolean mix = nearest != null && !refers;
		List<Candidate> found = earlyKeywords != null && mix && excluded.isEmpty() && plan.core().isEmpty()
				&& fromYear == null && parts.size() < 2 && plan.keywords().equals(earlyWords) ? joined(earlyKeywords)
						: null;
		run.earlyKeywords = found != null;
		if (found == null) {
			found = keywordCandidates(plan, parts, fromYear, input.planLibraryIds(), excluded,
					mix ? keywordLimit : max - chosen.size(), run);
		}
		run.mark("keyword");
		if (!mix) {
			for (Candidate candidate : found) {
				// a book the import put in after this request's snapshot is left out
				if (known.containsKey(candidate.bookId())) {
					chosen.add(candidate.bookId());
					planCount.put(candidate.bookId(), candidate.planCount());
				}
			}
		}
		else {
			List<Long> keywordIds = new ArrayList<>();
			for (Candidate candidate : found) {
				if (known.containsKey(candidate.bookId())) {
					keywordIds.add(candidate.bookId());
					planCount.put(candidate.bookId(), candidate.planCount());
				}
			}
			List<Long> vectorIds = vectorCandidates(parts.size() >= 2 ? partNearest(parts, vectorSettings, nearest) : nearest,
					known, excluded, input.planLibraryIds(), planCount,
					vectorSettings, fromYear);
			run.vectorCount = vectorIds.size();
			run.mark("vector");
			// one edition of a title: the mix brings the same book from both lists under different ids
			List<Long> mixed = mlStep == null ? Fusion.rrf(keywordIds, vectorIds, vectorSettings.rrfK())
					: Fusion.rrf(keywordIds, vectorIds, vectorSettings.rrfK(),
							id -> mlFirstCall.bonus(id, mlStep.plan().shelves()),
							id -> mlFirstCall.rulesOut(id, mlStep.plan().shelves()));
			for (long bookId : mixed) {
				if (chosen.size() >= max) {
					break;
				}
				if (titles.add(known.get(bookId).titleKey())) {
					chosen.add(bookId);
				}
			}
		}
		run.mark("mix");
		if (chosen.isEmpty()) {
			noMatchChoices(input, history, run, "");
			finish(run, PickResult.NO_MATCH, "no_match");
			return;
		}
		if (run.gone) {
			return;
		}
		List<Long> ids = List.copyOf(chosen);
		Map<Long, String> lines = candidates.lines(ids);
		Map<Long, Integer> years = publishYears(ids);
		List<Choice> choices = ids.stream()
			.map(id -> new Choice(known.get(id).title(), known.get(id).author(), lines.get(id), years.get(id)))
			.toList();
		run.candidateCount = ids.size();
		// covers and summaries go on cards only, not to the call: they are read while it runs (user 2026-10-27, 응답 시간
		// 2안), and the first card waits for them if need be
		Cards cards = new Cards(known, listed, beside(() -> bookTexts.coverUrls(ids)),
				beside(() -> bookTexts.summaries(ids)), planCount);
		run.mark("cards");
		picks(input, history, ids, choices, cards, pointsBack, talk, titleNote, run);
	}

	/** An earlier card as the calls see it: the title, and the author when known. */
	static String shownLine(SearchBook book) {
		return book.author() == null || book.author().isBlank() ? book.title()
				: book.title() + " / 저자: " + book.author();
	}

	/** How the pick call may ask back for a question of talk words. */
	enum AskMode {

		/** As for any follow-up. */
		NORMAL,

		/** Never: the user already said they cannot choose, or where to go back to is known. */
		NEVER,

		/** Always, with choices from the dialogue's topics: what the words point at is not known. */
		FORCE

	}

	/**
	 * What a question of talk words makes the search do.
	 * @param topic the question the search goes on from (the topic the words point at)
	 * @param excludeShown whether the books shown before stay out (「그거 말고」 after cards)
	 * @param note the line the pick call gets about what the user meant
	 */
	record Talk(TalkRefs.Kind kind, String topic, AskMode mode, boolean excludeShown, String note) {
	}

	/** The talk words' plan (TalkRefs), or null for a question that names its own topic. */
	static Talk talk(Input input) {
		return talk(input, null);
	}

	/**
	 * What 「아까」 goes back to by order words and the question vector, or null to leave it to the words
	 * (TalkRefs#back).
	 */
	interface BackTopics {

		/** @param questions the earlier questions one by one (a topic joins a question and the answer to its ask-back) */
		String of(String question, List<String> topics, List<String> questions);

	}

	/** @param backTopics picks the topics 「아까」 points at, or null for the words alone */
	static Talk talk(Input input, BackTopics backTopics) {
		if (input.history().isEmpty()) {
			return null;
		}
		TalkRefs.Ref ref = TalkRefs.of(input.question());
		if (ref == null) {
			return null;
		}
		List<String> questions = input.history().stream().map(Turn::question).toList();
		List<Boolean> asked = input.history().stream().map(t -> t.askedBack() != null).toList();
		List<String> topics = TalkRefs.topics(questions, asked);
		if (topics.isEmpty()) {
			return null;
		}
		Turn last = input.history().getLast();
		boolean lastAsked = last.askedBack() != null;
		boolean askedTwice = lastAsked && input.history().size() > 1
				&& input.history().get(input.history().size() - 2).askedBack() != null;
		String current = topics.getLast();
		String offered = String.join("」·「", last.askChoices());
		String q = input.question().strip();
		Talk unknown = new Talk(ref.kind(), current, AskMode.FORCE, false, "사용자의 「" + q + "」가 앞 대화의 무엇을 가리키는지 "
				+ "분명하지 않다. 반드시 되묻는다. 고를 답은 이 대화에 나온 주제(「" + String.join("」, 「", topics)
				+ "」)에서 짓고, 새로 찾고 싶은 게 있으면 적어 달라는 뜻도 덧붙인다.");
		return switch (ref.kind()) {
			// after cards too (a one-word question is answered at once since 2026-10-29, so 「모르겠어」 often follows cards)
			case DONT_KNOW -> lastAsked
					? new Talk(ref.kind(), current, AskMode.NEVER, false, "사용자가 되물은 말에 「" + q + "」라고 답해 어느 쪽인지 "
							+ "정하지 못했다. 다시 묻지 말고, 앞에서 낸 고를 답(「" + offered + "」)의 갈래마다 2~3권씩 고루 섞어 "
							+ "고른다. 머리말에는 여러 갈래로 골라 봤다고 쓴다.")
					: last.bookIds().isEmpty() ? null
							: new Talk(ref.kind(), current, AskMode.NEVER, false, "사용자가 앞에서 보인 책을 보고 「" + q + "」라고 했다. "
									+ "다시 묻지 말고, 「" + current + "」 주제에서 여러 갈래의 책을 고루 섞어 고른다(앞에서 보인 책도 "
									+ "다시 골라도 된다). 머리말에는 여러 갈래로 골라 봤다고 쓴다.");
			case NOT_THAT -> {
				if (ref.that()) {
					yield unknown;
				}
				if (lastAsked) {
					yield askedTwice
							? new Talk(ref.kind(), current, AskMode.NEVER, false, "사용자가 다시 되물은 고를 답(「" + offered
									+ "」)에도 원하는 것이 없다고 했다. 더 묻지 말고, 「" + current + "」에서 그 고를 답과 다른 갈래의 "
									+ "책을 고루 섞어 고른다.")
							: new Talk(ref.kind(), current, AskMode.FORCE, false, "사용자가 앞에서 낸 고를 답(「" + offered
									+ "」) 가운데 원하는 것이 없다고 했다. 그 고를 답과 다른 「" + current + "」의 갈래로 다시 "
									+ "되묻는다. 앞의 고를 답과 같은 뜻의 고를 답은 다시 쓰지 않고, 아래 책들과 이 대화에 나온 "
									+ "주제에서 새 갈래를 짓는다.");
				}
				yield last.bookIds().isEmpty() ? unknown
						: new Talk(ref.kind(), current, AskMode.NORMAL, true, "사용자가 방금 보인 책이 아닌 다른 책을 원한다(「" + q
								+ "」). 「" + current + "」 주제에서 방금 보인 책과 다른 책을 고른다. 무엇이 마음에 들지 않았는지 "
								+ "알 수 없으면 이 주제 안의 갈래로 되물어도 된다.");
			}
			case BACK_TO -> {
				String picked = backTopics == null ? null : backTopics.of(q, topics, questions);
				String chosen = picked == null ? TalkRefs.back(ref, topics) : picked;
				yield chosen == null ? unknown
						: new Talk(ref.kind(), chosen, AskMode.NEVER, false, "사용자가 앞 대화의 「" + chosen + "」 이야기로 돌아가려 "
								+ "한다(「" + q + "」). 그 주제에 맞는 책을 고른다(앞에서 보인 책도 다시 골라도 된다). 머리말에서 "
								+ "그 주제로 돌아왔다고 자연스럽게 말한다.");
			}
		};
	}

	/**
	 * The neighbours of the books shown last that the plan libraries hold and that were not shown before, the nearest
	 * of each book first, then the second nearest of each, and so on; at most {@value #MAX_NEIGHBOUR_CANDIDATES}.
	 */
	private static List<Long> nearby(List<Long> lastShown, Map<Long, List<Long>> found, Set<Long> shownBefore,
			Predicate<Long> held) {
		List<Long> nearby = new ArrayList<>();
		Set<Long> taken = new HashSet<>(shownBefore);
		for (int rank = 0; nearby.size() < MAX_NEIGHBOUR_CANDIDATES; rank++) {
			boolean more = false;
			for (long shown : lastShown) {
				List<Long> ids = found.getOrDefault(shown, List.of());
				if (rank >= ids.size()) {
					continue;
				}
				more = true;
				long id = ids.get(rank);
				if (held.test(id) && taken.add(id) && nearby.size() < MAX_NEIGHBOUR_CANDIDATES) {
					nearby.add(id);
				}
			}
			if (!more) {
				break;
			}
		}
		return nearby;
	}

	/**
	 * The neighbours of the named book's editions, the first edition's nearest first; not an edition, not a book whose
	 * title holds the named title (its other volumes come from the keywords), not shown before; at most
	 * {@value #MAX_NAMED_NEIGHBOURS}.
	 */
	private List<Long> namedNeighbours(NamedBook named, Map<Long, SearchBook> known, Set<Long> shownBefore) {
		Map<Long, List<Long>> found = neighbours.of(named.editions());
		LinkedHashSet<Long> near = new LinkedHashSet<>();
		for (long edition : named.editions()) {
			for (long bookId : found.getOrDefault(edition, List.of())) {
				SearchBook book = known.get(bookId);
				if (near.size() < MAX_NAMED_NEIGHBOURS && book != null && !named.editions().contains(bookId)
						&& !shownBefore.contains(bookId) && !NamedBook.key(book.title()).contains(named.key())) {
					near.add(bookId);
				}
			}
		}
		return List.copyOf(near);
	}

	/**
	 * The question vector's nearest books the plan libraries hold, not excluded, at most {@code vectorDepth}, their
	 * plan library counts put in {@code planCount}. Empty when the vector is late (over {@code maxWait}) or failed: the
	 * search goes on with keywords.
	 */
	private List<Long> vectorCandidates(CompletableFuture<List<Long>> nearest, Map<Long, SearchBook> known,
			Set<Long> excluded, Set<Long> planLibraryIds, Map<Long, Integer> planCount,
			AiSearchProperties.Vector settings, Integer fromYear) {
		List<Long> near;
		try {
			near = nearest.get(settings.maxWait().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			log.info("ai search: the question vector was late");
			return List.of();
		}
		catch (ExecutionException e) {
			log.warn("ai search: the question vector failed: {}", e.getCause().getClass().getName());
			return List.of();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return List.of();
		}
		List<Long> nearer = near.stream().filter(known::containsKey).filter(id -> !excluded.contains(id)).toList();
		// the nearest NEAREST_POOL times the depth are read; recent books first for 「최신」, then as many as before
		if (fromYear != null) {
			// within the nearest RECENT_WINDOW times the depth only: further down a recent book is off the topic (「요새꺼」
			// after 과학·철학 brought novels, the local replay of 2026-10-29)
			List<Long> recent = new ArrayList<>();
			List<Long> older = new ArrayList<>();
			List<Long> window = nearer.subList(0, Math.min(nearer.size(), settings.vectorDepth() * RECENT_WINDOW));
			for (long id : window) {
				Integer year = known.get(id).year();
				(year != null && year >= fromYear ? recent : older).add(id);
			}
			recent.addAll(older);
			nearer = recent;
		}
		List<Long> keep = nearer.subList(0, Math.min(nearer.size(), settings.vectorDepth() * 2));
		Map<Long, Integer> counts = candidates.planCounts(keep, planLibraryIds);
		List<Long> held = keep.stream().filter(counts::containsKey).limit(settings.vectorDepth()).toList();
		held.forEach(id -> planCount.put(id, counts.get(id)));
		return held;
	}

	/** The candidates' publish years from the catalog, one query; none when the catalog fails (the search goes on). */
	private Map<Long, Integer> publishYears(List<Long> ids) {
		Map<Long, Integer> years = new HashMap<>();
		try {
			for (BookCards.BookCard card : bookCards.cards(ids)) {
				if (card.publishedYear() != null) {
					years.put(card.bookId(), card.publishedYear());
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("ai search: the publish years were not read: {}", e.getClass().getName());
		}
		return years;
	}

	/**
	 * The keyword candidates, best first, at most {@code limit}: one search, or for topics listed side by side (「A나 B」,
	 * 「다 같이」) one search a topic, its own words counting first, taken in turn (user 2026-10-29: one search for
	 * 「세계역사나 과학사나」 filled the list from one side). Recency words are no search words; 「최신」 puts recent books first.
	 */
	private List<Candidate> keywordCandidates(KeywordPlan plan, List<String> parts, Integer fromYear,
			Set<Long> planLibraryIds, Set<Long> excluded, int limit, Run run) {
		List<String> keywords = fromYear == null ? plan.keywords()
				: plan.keywords().stream().filter(word -> !Recency.isWord(word)).toList();
		if (parts.size() < 2) {
			return candidates.find(keywords, plan.core(), planLibraryIds, excluded, limit, fromYear);
		}
		run.parts = parts.size();
		int each = (limit + parts.size() - 1) / parts.size();
		List<List<Candidate>> lists = new ArrayList<>();
		for (String part : parts) {
			List<String> own = MlFirstCall.firstKeywords(part).stream().filter(word -> !Recency.isWord(word)).toList();
			LinkedHashSet<String> words = new LinkedHashSet<>(own);
			words.addAll(keywords);
			lists.add(candidates.find(List.copyOf(words), own, planLibraryIds, excluded, each, fromYear));
		}
		List<Candidate> taken = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (int rank = 0; taken.size() < limit; rank++) {
			boolean any = false;
			for (List<Candidate> list : lists) {
				if (rank < list.size()) {
					any = true;
					if (seen.add(list.get(rank).bookId()) && taken.size() < limit) {
						taken.add(list.get(rank));
					}
				}
			}
			if (!any) {
				break;
			}
		}
		return taken;
	}

	/** The call's note with the line for 「최신」 added when recent books were asked for (user 2026-10-29). */
	static String withRecency(String note, Run run) {
		if (run.fromYear == null) {
			return note;
		}
		String recent = "사용자는 최근에 나온 책을 원한다. " + run.fromYear + "년 이후에 나온 책을 먼저 고르고, 오래된 책은 맞는 "
				+ "최근 책이 모자랄 때만 고른다. 머리말에서 최근 책으로 골랐다고 말해도 된다.";
		return note == null ? recent : note + " " + recent;
	}

	/** Whether every word of the question only says 「recent」 (「요새꺼」, 「최신책」, 「최근꺼만」). */
	static boolean onlyRecency(String question) {
		List<String> words = MlFirstCall.firstKeywords(question);
		return !words.isEmpty() && words.stream().allMatch(Recency::isWord);
	}

	/** How long a follow-up waits for the vectors of its own words (the reply, the parts, the topics). */
	private static final Duration INTENT_WAIT = Duration.ofMillis(1500);

	/**
	 * Whether a reply to an ask-back takes every choice: the vector of the reply against example replies, the words
	 * besides; a turning-down word (「다 별로」) never (user 2026-10-29: on the rude test sentences 55 of 60 against 51 by
	 * the words alone). With the vectors off, the words alone.
	 */
	private boolean allChoices(String reply) {
		if (NOT_ALL.matcher(reply).find()) {
			return false;
		}
		boolean words = ALL_CHOICES.matcher(reply.strip()).find();
		List<float[]> found = intents.encode(List.of(reply), INTENT_WAIT);
		String label = found == null ? null : intents.label(IntentVectors.ALL_CHOICES, found.getFirst());
		if (label == null) {
			return words;
		}
		return (label.equals("all") || words) && !label.equals("reject") && !label.equals("other");
	}

	/**
	 * The first year of 「최신」: a year the text names, else a recency word whose text means newly published books by
	 * its vector (「요즘 지쳤는데」 does not; user 2026-10-29: 63 of 70 rude test sentences against 55 by the words alone).
	 * The texts are the question, then what it carries on, newest first. With the vectors off, the words alone.
	 */
	private Integer fromYear(List<String> texts, String question, float[] questionVector) {
		int thisYear = java.time.Year.now(SEOUL).getValue();
		for (String text : texts) {
			if (text == null) {
				continue;
			}
			Integer since = Recency.since(text, thisYear);
			if (since != null) {
				return since;
			}
			if (!Recency.mentions(text)) {
				continue;
			}
			float[] vector = text.equals(question) ? questionVector : null;
			if (vector == null) {
				List<float[]> found = intents.encode(List.of(text), INTENT_WAIT);
				vector = found == null ? null : found.getFirst();
			}
			String label = intents.label(IntentVectors.RECENCY, vector);
			if (label == null || label.equals("recent")) {
				return thisYear - Recency.YEARS;
			}
		}
		return null;
	}

	/**
	 * What 「아까」 points at: the topics named by order words (「처음 거랑 끝에거」, 「앞에 둘」, 「전부」) or holding a word
	 * of the question, then for every other topic word the earlier question its vector is nearest, with the word itself
	 * when that question does not hold it; null when nothing is picked (user 2026-10-29: 35 of 40 rude test sentences
	 * against 4 by the words alone). The vector goes to the questions one by one, not to the topics: a topic joins a
	 * question and the answer to its ask-back, and 「세게사 책」 + 「과학싸나 철학」 sent 「아까 세계사 말한거 최신으로」 to
	 * science and philosophy with the typo 세게사 and without 세계사 (production v1.22.1, 2026-10-30: 0 books).
	 */
	private String backTopics(String question, List<String> topics, List<String> questions) {
		TreeSet<Integer> picked = new TreeSet<>(TalkRefs.ordered(question, topics.size()));
		// a topic a word of the question is in counts as well (「최신책 아까 세계역사나」: the vector of the whole reply alone
		// took 「최신책」 and lost 세계역사, the local replay of 2026-10-29)
		List<String> rest = TalkRefs.rest(question);
		for (int t = 0; t < topics.size(); t++) {
			String topic = topics.get(t).toLowerCase(java.util.Locale.ROOT);
			if (rest.stream().anyMatch(topic::contains)) {
				picked.add(t);
			}
		}
		List<String> chosen = new ArrayList<>(picked.stream().map(topics::get).toList());
		// the topic words only: talk words and 「최신으로」 pulled the reply towards a topic 「요새꺼」 (local replay)
		String named = String.join(" ", TalkRefs.rest(TalkRefs.withoutOrder(question)).stream()
			.filter(word -> !Recency.isWord(word))
			.toList());
		List<String> parts = (named.isBlank() ? List.<String>of() : TopicParts.of(named))
			.stream()
			.filter(part -> !TalkRefs.rest(part).isEmpty())
			.toList();
		List<String> earlier = questions.stream().filter(q -> TalkRefs.of(q) == null).map(String::strip).distinct().toList();
		if (!parts.isEmpty() && !earlier.isEmpty()) {
			List<String> texts = new ArrayList<>(parts);
			texts.addAll(earlier);
			List<float[]> found = intents.encode(texts, INTENT_WAIT);
			if (found != null) {
				for (int i = 0; i < parts.size(); i++) {
					String part = parts.get(i);
					String joined = String.join(" ", chosen).toLowerCase(java.util.Locale.ROOT);
					if (TalkRefs.rest(part).stream().allMatch(joined::contains)) {
						continue;
					}
					int best = -1;
					float bestScore = BACK_TOPIC_FLOOR;
					for (int q = 0; q < earlier.size(); q++) {
						float score = IntentVectors.dot(found.get(i), found.get(parts.size() + q));
						if (score >= bestScore) {
							best = q;
							bestScore = score;
						}
					}
					if (best < 0) {
						continue;
					}
					String nearest = earlier.get(best);
					if (!joined.contains(nearest.toLowerCase(java.util.Locale.ROOT))) {
						chosen.add(nearest);
					}
					// the user's own spelling as well (「세계사」 for the earlier 「세게사」)
					if (!TalkRefs.rest(part).stream().allMatch(nearest.toLowerCase(java.util.Locale.ROOT)::contains)) {
						chosen.add(part);
					}
				}
			}
		}
		return chosen.isEmpty() ? null : String.join(" ", chosen);
	}

	/** The least closeness of a word of 「아까」 to the topic it is taken for (scripts/aisearch/eval/intent_mixed.py). */
	static final float BACK_TOPIC_FLOOR = 0.3f;

	/**
	 * The nearest books of each listed topic (「A나 B」), taken in turn: one vector for 「세계사나 과학사」 brought one side
	 * (user 2026-10-29). The question's own nearest when the topics' vectors do not come.
	 */
	private CompletableFuture<List<Long>> partNearest(List<String> parts, AiSearchProperties.Vector settings,
			CompletableFuture<List<Long>> whole) {
		List<float[]> found = intents.encode(parts, INTENT_WAIT);
		if (found == null) {
			return whole;
		}
		int each = settings.vectorDepth() * NEAREST_POOL / parts.size();
		List<List<Long>> lists = found.stream().map(v -> vectors.nearest(v, each)).toList();
		List<Long> taken = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (int rank = 0; rank < each; rank++) {
			for (List<Long> list : lists) {
				if (rank < list.size() && seen.add(list.get(rank))) {
					taken.add(list.get(rank));
				}
			}
		}
		return CompletableFuture.completedFuture(taken);
	}

	/** Starts a read beside the search. */
	private static <T> CompletableFuture<T> beside(Supplier<T> read) {
		return CompletableFuture.supplyAsync(read, BESIDE);
	}

	/** The early keyword search's books, or null when it failed (the search then queries again). */
	private static List<Candidate> joined(CompletableFuture<List<Candidate>> early) {
		try {
			return early.join();
		}
		catch (CompletionException e) {
			log.warn("ai search: the early keyword search failed ({})", e.getCause().getClass().getName());
			return null;
		}
	}

	/** The ML first call's plan and the vector candidates of its search vector. */
	/** @param vector the question's own vector (IntentVectors) */
	private record MlStep(MlFirstCall.Plan plan, List<Long> nearest, float[] vector) {
	}

	/**
	 * The ML first step: the question's vector (and for a follow-up the earlier question's and the two joined, at the
	 * same time), the plan from them, the nearest books of the search vector. Null when a vector fails or comes later
	 * than {@code maxWait}: the search then asks Haiku.
	 */
	/**
	 * @param earlierVectorOnly the question only says 「recent」 (「요새꺼」): the books are searched by the earlier
	 *     question's vector and the year does the rest (the joined vector brought novels, the local replay of 2026-10-29)
	 */
	private MlStep mlStep(String question, String earlierQuestion, boolean pointsBack, boolean earlierVectorOnly,
			AiSearchProperties.Vector settings, Run run) {
		// the earlier question goes to the encoder too (this process or the embedding server, never logged)
		List<String> texts = earlierQuestion == null ? List.of(question)
				: List.of(question, earlierQuestion, earlierQuestion + " " + question);
		CompletableFuture<List<float[]>> lookup = vectors.vectors(texts);
		try {
			List<float[]> found = lookup.get(settings.maxWait().toMillis(), TimeUnit.MILLISECONDS);
			run.mark("encode");
			float[] vector = found.get(0);
			MlFirstCall.Plan plan = mlFirstCall.plan(question, vector, earlierQuestion,
					earlierQuestion == null ? null : found.get(1), earlierQuestion == null ? null : found.get(2),
					pointsBack);
			run.mark("ml");
			float[] search = earlierVectorOnly && earlierQuestion != null ? found.get(1)
					: plan.joinedVector() ? found.get(2) : vector;
			List<Long> nearestBooks = vectors.nearest(search, settings.vectorDepth() * NEAREST_POOL);
			run.mark("nearest");
			return new MlStep(plan, nearestBooks, vector);
		}
		catch (TimeoutException e) {
			lookup.cancel(true);
			log.info("ai search: the question vector was late, haiku makes the first step");
		}
		catch (ExecutionException e) {
			if (!(e.getCause() instanceof QuestionVectors.Unavailable)) {
				log.warn("ai search: the question vector failed ({}), haiku makes the first step",
						e.getCause().getClass().getName());
			}
		}
		catch (InterruptedException e) {
			lookup.cancel(true);
			Thread.currentThread().interrupt();
		}
		catch (RuntimeException e) {
			log.warn("ai search: the ml first call failed ({}), haiku makes the first step", e.getClass().getName());
		}
		return null;
	}

	/**
	 * A question that is not a book request (user 2026-10-29 「책 밖 대화는 부드럽게」): one call ({@link Prompts#CHAT_SYSTEM})
	 * answers it and leads back to books with choices that fit the dialogue; it goes out as an ask-back. Failing, the
	 * fixed ask-back with three drawn examples goes out instead (user 2026-10-26).
	 */
	private void chat(Input input, List<ShownTurn> history, Run run) {
		LlmCall call = new LlmCall(Prompts.CHAT_SYSTEM, Prompts.chatMessages(history, input.question()),
				Prompts.CHAT_SCHEMA, properties.firstCall().siteMaxTokens());
		try {
			Result result = llm.complete(call, properties.pickTimeout());
			count(run, result.usage(), call.maxTokens());
			JsonNode answer = json.readTree(result.text());
			String question = answer.path("question").asString("").strip();
			List<String> choices = new ArrayList<>();
			for (JsonNode choice : answer.path("choices")) {
				String text = choice.asString("").strip();
				if (!text.isEmpty() && choices.size() < MAX_ASK_CHOICES) {
					choices.add(text.codePointCount(0, text.length()) <= MAX_ASK_CHOICE_CHARS ? text
							: text.substring(0, text.offsetByCodePoints(0, MAX_ASK_CHOICE_CHARS)));
				}
			}
			if (!question.isEmpty() && !choices.isEmpty()) {
				run.ask = "chat";
				run.send(AiSearchEvents.ASK, new AiSearchEvents.Ask(question, List.copyOf(choices)));
				finish(run, PickResult.SKIPPED, "asked");
				return;
			}
		}
		catch (LlmException e) {
			if (e.failure() != Failure.NOT_CONFIGURED) {
				count(run, e.usage(), call.maxTokens());
			}
		}
		catch (JacksonException e) {
			log.warn("ai search: the chat answer was not the schema's");
		}
		List<String> examples = new ArrayList<>(Prompts.NOT_BOOK_CHOICES);
		Collections.shuffle(examples);
		run.ask = "not-book-ask";
		run.send(AiSearchEvents.ASK, new AiSearchEvents.Ask(Prompts.NOT_BOOK_ASK, List.copyOf(examples.subList(0, 3))));
		finish(run, PickResult.SKIPPED, "asked");
	}

	/**
	 * The questions to go on with after a search that found no book (user 2026-10-30 「선택도 LLM이 그때그때 다른 걸로」:
	 * the web drew three fixed examples, 「안녕」 taken as a book question got 「반전과 추리가 돋보이는 최신 미스터리 소설」):
	 * one call ({@link Prompts#NO_MATCH_SYSTEM}) writes choices for this question and the dialogue, sent as an ask-back;
	 * its question only when no head went out. Failing, nothing more goes out and the web shows its examples.
	 */
	private void noMatchChoices(Input input, List<ShownTurn> history, Run run, String head) {
		if (!pick.askBack()) {
			return;
		}
		LlmCall call = new LlmCall(Prompts.NO_MATCH_SYSTEM, Prompts.chatMessages(history, input.question()),
				Prompts.CHAT_SCHEMA, properties.firstCall().siteMaxTokens());
		try {
			Result result = llm.complete(call, properties.pickTimeout());
			count(run, result.usage(), call.maxTokens());
			JsonNode answer = json.readTree(result.text());
			List<String> choices = new ArrayList<>();
			for (JsonNode choice : answer.path("choices")) {
				String text = choice.asString("").strip();
				if (!text.isEmpty() && choices.size() < MAX_ASK_CHOICES) {
					choices.add(text.codePointCount(0, text.length()) <= MAX_ASK_CHOICE_CHARS ? text
							: text.substring(0, text.offsetByCodePoints(0, MAX_ASK_CHOICE_CHARS)));
				}
			}
			String question = head.isBlank() ? answer.path("question").asString("").strip() : "";
			if (!choices.isEmpty()) {
				run.ask = "no-match choices";
				run.send(AiSearchEvents.ASK, new AiSearchEvents.Ask(question, List.copyOf(choices)));
			}
		}
		catch (LlmException e) {
			if (e.failure() != Failure.NOT_CONFIGURED) {
				count(run, e.usage(), call.maxTokens());
			}
		}
		catch (JacksonException e) {
			log.warn("ai search: the no-match choices were not the schema's");
		}
	}

	/**
	 * A question about 책첵 or about using the e-libraries (ML way {@code site}, user 2026-10-26): one call answers
	 * from the site's own text ({@link Prompts#SITE_SYSTEM}); the answer goes out as the head and the search ends as
	 * {@code site}. Failing, it ends as {@code no_match} like a question that is not about books.
	 */
	private void site(Input input, List<ShownTurn> history, Run run) {
		LlmCall call = new LlmCall(Prompts.SITE_SYSTEM, Prompts.siteMessages(history, input.question()),
				Prompts.SITE_SCHEMA, properties.firstCall().siteMaxTokens());
		try {
			Result result = llm.complete(call, properties.pickTimeout());
			count(run, result.usage(), call.maxTokens());
			String answer = json.readTree(result.text()).path("answer").asString("").strip();
			if (!answer.isEmpty()) {
				run.send(AiSearchEvents.HEAD_DELTA, new Text(answer));
				finish(run, PickResult.SKIPPED, "site");
				return;
			}
		}
		catch (LlmException e) {
			if (e.failure() != Failure.NOT_CONFIGURED) {
				count(run, e.usage(), call.maxTokens());
			}
		}
		catch (JacksonException e) {
			log.warn("ai search: the site answer was not the schema's");
		}
		finish(run, PickResult.NO_MATCH, "no_match");
	}

	private KeywordPlan keywords(String question, List<ShownTurn> history, Run run) {
		LlmCall call = new LlmCall(Prompts.KEYWORD_SYSTEM, Prompts.keywordMessages(history, question),
				Prompts.KEYWORD_SCHEMA, properties.keywordMaxTokens());
		KeywordResult failed;
		try {
			Result result = llm.complete(call, properties.keywordTimeout());
			count(run, result.usage(), call.maxTokens());
			JsonNode answer = json.readTree(result.text());
			List<String> keywords = new ArrayList<>();
			for (JsonNode keyword : answer.path("keywords")) {
				if (keyword.isString()) {
					keywords.add(keyword.asString());
				}
			}
			List<String> core = new ArrayList<>();
			for (JsonNode word : answer.path("core")) {
				if (word.isString()) {
					core.add(word.asString());
				}
			}
			if (!answer.path("notBook").isBoolean() || !answer.path("keywords").isArray()) {
				throw new IllegalStateException("the keyword answer has not the schema's fields");
			}
			return new KeywordPlan(answer.path("notBook").asBoolean(), answer.path("titleLike").asBoolean(false),
					answer.path("title").asString(""), answer.path("refersToShown").asBoolean(false), keywords, core,
					KeywordResult.OK);
		}
		catch (LlmException e) {
			if (e.failure() != Failure.NOT_CONFIGURED) {
				count(run, e.usage(), call.maxTokens());
			}
			failed = e.failure() == Failure.TIMEOUT ? KeywordResult.TIMEOUT : KeywordResult.FAILED;
		}
		catch (JacksonException | IllegalStateException e) {
			failed = KeywordResult.FAILED;
		}
		// spec R6-1: the question's own words, nothing on the screen tells
		return new KeywordPlan(false, false, "", false, List.of(question.split("\\s+")), List.of(), failed);
	}

	/**
	 * One word of at least two letters, not counting Hangul jamo (「ㅋㅋ」) or marks (「??」): a topic like 「사진」 or
	 * 「AI」, answered at once (user 2026-10-29).
	 */
	static boolean oneWordTopic(String question) {
		String word = question.strip();
		if (word.isEmpty() || word.chars().anyMatch(Character::isWhitespace)) {
			return false;
		}
		long letters = word.codePoints().filter(Character::isLetter).filter(c -> c < 0x3131 || c > 0x318E).count();
		return letters >= 2;
	}

	/** The words of a question, split at spaces. */
	static int words(String question) {
		String text = question.strip();
		return text.isEmpty() ? 0 : text.split("\\s+").length;
	}

	/**
	 * @param titleNote the line for a question that is a book's title (its books go first), or null
	 */
	private void picks(Input input, List<ShownTurn> history, List<Long> ids, List<Choice> choices, Cards cards,
			boolean pointsBack, Talk talk, String titleNote, Run run) {
		int maxPicks = properties.limits().picks();
		int maxChars = properties.limits().cardTextMaxChars();
		Set<Long> seen = new HashSet<>();
		long[] current = { -1 };
		long[] pending = { -1 };
		int[] currentChars = { 0 };
		int[] currentRaw = { 0 };
		// a question joining earlier topics (EarlierTopics: 「둘이 합쳐진 거」) or answering an ask-back is never asked
		// back: the earlier questions say what it means (user 2026-10-26, 2 wrong asks in the test; 2026-10-27)
		// a long first question is clear enough to pick from at once (ASK_QUESTION_WORDS)
		// talk words decide it themselves (plan 2026-10-29): never after 「모르겠어」, always when what they point at is
		// not known
		// a one-word question is answered at once, its common senses mixed (user 2026-10-29 「한 단어도 그걸로 답변」);
		// pick.ask-one-word brings the ask-back of v1.17.0 to v1.20.5 back
		// first questions only: in a dialogue one word goes on with the topic (user 2026-10-29: 「최신책」 mixed fields)
		boolean oneWord = talk == null && titleNote == null && input.history().isEmpty() && oneWordTopic(input.question())
				&& !pick.askOneWord();
		boolean askBack = pick.askBack() && titleNote == null && !oneWord && (talk != null ? talk.mode() != AskMode.NEVER
				: !pointsBack && (!input.history().isEmpty() || words(input.question()) <= ASK_QUESTION_WORDS));
		String oneWordNote = oneWord && pick.askBack() ? "질문이 낱말 하나(「" + input.question().strip() + "」)다. 되묻지 않는다. "
				+ "뜻이 여럿이면 흔한 갈래 2~3개의 책을 고루 섞어 고르고, 머리말에 어떤 갈래로 골랐는지 한 문장으로 쓴다." : null;
		// a short question gets the candidates' big shelves, so the call sees whether they split (「사진」, user 2026-10-27)
		Map<String, Integer> shelves = askBack && (words(input.question()) <= SHORT_QUESTION_WORDS
				|| talk != null && talk.mode() == AskMode.FORCE) ? mlFirstCall.shelfCounts(ids) : Map.of();
		// the ask-back question and choices when the call asks instead of picking (pick.ask-back, user 2026-10-26)
		StringBuilder askQuestion = new StringBuilder();
		// the summary by situation, kept whole and sent after the cards once checked (user 2026-10-29: 「1, 16, 18번」 went out)
		StringBuilder tail = new StringBuilder();
		StringBuilder head = new StringBuilder();
		boolean[] tailCut = { false };
		String[] answer = { null };
		List<String> askChoices = new ArrayList<>();
		PickStreamParser parser = new PickStreamParser(new PickStreamParser.Listener() {

			@Override
			public void askQuestion(String question) {
				askQuestion.append(question.strip());
			}

			@Override
			public void askChoice(String choice) {
				String text = choice.strip();
				if (!askQuestion.isEmpty() && !text.isEmpty() && askChoices.size() < MAX_ASK_CHOICES) {
					askChoices.add(text.codePointCount(0, text.length()) <= MAX_ASK_CHOICE_CHARS ? text
							: text.substring(0, text.offsetByCodePoints(0, MAX_ASK_CHOICE_CHARS)));
				}
			}

			@Override
			public void headText(String text) {
				if (askQuestion.isEmpty()) {
					head.append(text);
					run.send(AiSearchEvents.HEAD_DELTA, new Text(text));
				}
			}

			@Override
			public void pickNo(long no) {
				current[0] = -1;
				pending[0] = -1;
				if (!askQuestion.isEmpty() || run.shown.size() >= maxPicks || no < 1 || no > ids.size() || !seen.add(no)) {
					return;
				}
				// the card waits for the pick's fit
				pending[0] = ids.get((int) no - 1);
			}

			@Override
			public void pickFit(long fit) {
				long bookId = pending[0];
				pending[0] = -1;
				if (bookId < 0 || fit < MIN_FIT) {
					return;
				}
				run.shown.add(bookId);
				run.send(AiSearchEvents.CARD, cards.card(run.shown.size(), bookId));
				current[0] = bookId;
				currentChars[0] = 0;
				currentRaw[0] = 0;
			}

			@Override
			public void pickText(String text) {
				if (current[0] < 0) {
					return;
				}
				int length = text.codePointCount(0, text.length());
				run.textChars += length;
				if (currentRaw[0] <= maxChars && currentRaw[0] + length > maxChars) {
					run.longCards++;
				}
				currentRaw[0] += length;
				if (currentChars[0] >= maxChars) {
					return;
				}
				int room = maxChars - currentChars[0];
				String piece = length <= room ? text : text.substring(0, text.offsetByCodePoints(0, room));
				currentChars[0] += Math.min(length, room);
				run.send(AiSearchEvents.CARD_DELTA, new CardDelta(current[0], piece));
			}

			@Override
			public void pickEnd() {
				current[0] = -1;
				pending[0] = -1;
			}

			@Override
			public void tailText(String text) {
				tail.append(text);
				// a summary running on (an 8-card answer stopped at its 15 s, production v1.21.1): the cards are out, the
				// rest is not read
				if (tail.length() > TAIL_MAX_CHARS) {
					tailCut[0] = true;
					throw new TailTooLong();
				}
			}

		});
		LlmCall call = new LlmCall(askBack ? Prompts.PICK_ASK_SYSTEM : Prompts.PICK_SYSTEM,
				Prompts.pickMessages(history, input.question(), choices,
						java.time.Year.now(SEOUL).getValue(), shelves,
						withRecency(talk != null ? talk.note() : titleNote != null ? titleNote : oneWordNote, run)),
				askBack ? Prompts.PICK_ASK_SCHEMA : Prompts.PICK_SCHEMA,
				properties.pickMaxTokens());
		boolean failed = false;
		try {
			boolean[] first = { true };
			Result result = llm.stream(call, properties.pickTimeout(), piece -> {
				if (run.gone || run.sink.gone()) {
					run.gone = true;
					throw new ClientGone();
				}
				if (first[0]) {
					first[0] = false;
					run.mark("llm-first");
				}
				parser.feed(piece);
			});
			run.mark("llm-done");
			count(run, result.usage(), call.maxTokens());
			failed = !parser.complete();
			answer[0] = result.text();
		}
		catch (LlmException e) {
			if (e.failure() != Failure.NOT_CONFIGURED) {
				count(run, e.usage(), call.maxTokens());
			}
			failed = !tailCut[0];
		}
		if (run.gone) {
			run.pickResult = run.shown.isEmpty() ? PickResult.SKIPPED : PickResult.PARTIAL;
			return;
		}
		if (!failed && !askQuestion.isEmpty()) {
			run.ask = "single-ask";
			run.send(AiSearchEvents.ASK, new AiSearchEvents.Ask(askQuestion.toString(), List.copyOf(askChoices)));
			finish(run, PickResult.SKIPPED, "asked");
		}
		else if (failed && run.shown.isEmpty()) {
			// spec R6-2: the first books without text
			for (int i = 0; i < Math.min(maxPicks, ids.size()); i++) {
				long bookId = ids.get(i);
				run.shown.add(bookId);
				run.send(AiSearchEvents.CARD, cards.card(run.shown.size(), bookId));
			}
			finish(run, PickResult.NO_TEXT, "no_text");
		}
		else if (failed) {
			finish(run, PickResult.PARTIAL, "partial");
		}
		else if (run.shown.isEmpty()) {
			noMatchChoices(input, history, run, head.toString());
			finish(run, PickResult.NO_MATCH, "no_match");
		}
		else {
			// nothing to sum up without two cards; the server writes the lines with the cards' titles (TailLines)
			JsonNode tailNode = answer[0] == null ? null : tailOf(answer[0]);
			String summary = run.shown.size() < 2 ? null : TailLines.render(tailNode, ids, run.shown, cards.known());
			if (summary != null) {
				run.send(AiSearchEvents.TAIL_DELTA, new Text(summary));
			}
			else if (tailCut[0]) {
				run.tail = "too long";
			}
			else if (run.shown.size() >= 2) {
				run.tail = TailLines.whyNone(tailNode);
			}
			finish(run, PickResult.OK, "ok");
		}
	}

	/**
	 * What a card is made from: title and author of the search book; the list cover (ADR-0164), else the edition's
	 * cover; the 책첵 summary (cycle 26: one kind, the list shows the same one). The covers and summaries are read from
	 * {@code catalog.BookTexts} while the call runs (R6, F1); a failed read leaves the cards without them.
	 */
	private record Cards(Map<Long, SearchBook> known, Map<Long, ListedBook> listed,
			CompletableFuture<Map<Long, String>> editionCovers, CompletableFuture<Map<Long, String>> summaries,
			Map<Long, Integer> planCount) {

		Cards {
			editionCovers = editionCovers.exceptionally(e -> read("covers", e));
			summaries = summaries.exceptionally(e -> read("summaries", e));
		}

		private static Map<Long, String> read(String what, Throwable e) {
			log.warn("ai search: the book {} were not read ({})", what,
					(e instanceof CompletionException && e.getCause() != null ? e.getCause() : e).getClass().getName());
			return Map.of();
		}

		Card card(int no, long bookId) {
			SearchBook book = known.get(bookId);
			ListedBook onList = listed.get(bookId);
			String cover = onList == null ? null : onList.coverPath();
			if (cover == null) {
				cover = editionCovers.join().get(bookId);
			}
			String summary = summaries.join().get(bookId);
			return new Card(no, bookId, book.title(), book.author(), cover, planCount.getOrDefault(bookId, 0), summary);
		}

	}

	private void count(Run run, Usage usage, int maxTokens) {
		run.inputTokens += usage.inputTokens();
		run.outputTokens += budget.addCall(usage, maxTokens);
	}

	private static void finish(Run run, PickResult result, String doneResult) {
		run.pickResult = result;
		run.send(AiSearchEvents.DONE, new Done(doneResult, run.shown));
	}

}
