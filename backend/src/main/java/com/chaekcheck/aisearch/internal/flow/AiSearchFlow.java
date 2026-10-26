package com.chaekcheck.aisearch.internal.flow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
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
import com.chaekcheck.aisearch.internal.quota.DailyBudget;
import com.chaekcheck.aisearch.internal.vector.QuestionVectors;
import com.chaekcheck.catalog.BookCards;
import com.chaekcheck.foryou.BookBlurbs;
import com.chaekcheck.foryou.BookCovers;
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

	private final BookCovers covers;

	private final BookBlurbs blurbs;

	private final QuestionVectors vectors;

	private final MlFirstCall mlFirstCall;

	private final BookCards bookCards;

	private final UsageLog usageLog;

	private final AiSearchProperties properties;

	private final JsonMapper json;

	AiSearchFlow(LlmClient llm, CandidateQuery candidates, DailyBudget budget, ListedBooks listedBooks,
			SearchBooks searchBooks, BookNeighbours neighbours, BookCovers covers, BookBlurbs blurbs,
			QuestionVectors vectors, MlFirstCall mlFirstCall, BookCards bookCards, UsageLog usageLog,
			AiSearchProperties properties, JsonMapper json) {
		this.llm = llm;
		this.candidates = candidates;
		this.budget = budget;
		this.listedBooks = listedBooks;
		this.searchBooks = searchBooks;
		this.neighbours = neighbours;
		this.covers = covers;
		this.blurbs = blurbs;
		this.vectors = vectors;
		this.mlFirstCall = mlFirstCall;
		this.bookCards = bookCards;
		this.usageLog = usageLog;
		this.properties = properties;
		this.json = json;
	}

	/**
	 * @param question trimmed, 2 to 200 characters
	 * @param turnNo the question's number in its conversation (1 to 20, from the web)
	 * @param history at most 5 earlier turns, oldest first
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

	/** An earlier question and the books its answer showed (at most 8). */
	public record Turn(String question, List<Long> bookIds) {

		public Turn {
			bookIds = List.copyOf(bookIds);
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

	/**
	 * Neighbours of the books shown before that a follow-up question pointing at them gets as candidates, at most
	 * (user 2026-10-25 "이어서 묻기에 앞 책의 이웃을 후보로", [안] 12).
	 */
	static final int MAX_NEIGHBOUR_CANDIDATES = 12;

	/**
	 * Neighbours of the book a "○○ 같은 책" question names that go first among the candidates, at most (user 2026-10-25,
	 * docs/reports/ai-search-eval-2026-10-25.md 3-3, [안] 20 as measured).
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

		/** The ML first call's way, or null when Haiku made the first step. */
		MlFirstCall.Route mlRoute;

		/** Whether the ML first call took a follow-up as a new topic. */
		boolean newTopic;

		/** The ML first call's relation of a follow-up to the earlier question ("first" on the first turn). */
		String relation;

		final List<Long> shown = new ArrayList<>();

		Run(AiSearchSink sink) {
			this.sink = sink;
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
		Run run = new Run(sink);
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
						run.named ? ", named book" : "", run.shown.size(), elapsedMs, run.inputTokens, run.outputTokens,
						run.gone ? ", client gone" : "");
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
		String earlierQuestion = input.history().isEmpty() ? null : input.history().getLast().question();
		MlStep mlStep = properties.firstCall().mode() == AiSearchProperties.FirstCallMode.ML && mlFirstCall.on()
				&& vectors.on() ? mlStep(input.question(), earlierQuestion, vectorSettings) : null;
		// the question vector is made while the first call runs; only the question goes in, never the history (the ML
		// first step above sends the earlier question too)
		CompletableFuture<List<Long>> nearest = mlStep != null ? CompletableFuture.completedFuture(mlStep.nearest())
				: vectors.on() ? vectors.nearest(input.question(), vectorSettings.vectorDepth() * 2) : null;
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
			history.add(new ShownTurn(turn.question(), shown.stream().map(id -> known.get(id).title()).toList()));
			shownBefore.addAll(shown);
			lastShown = shown;
		}

		KeywordPlan plan;
		if (mlStep != null) {
			MlFirstCall.Plan ml = mlStep.plan();
			run.mlRoute = ml.route();
			run.newTopic = ml.newTopic();
			run.relation = ml.relation();
			if (ml.route() == MlFirstCall.Route.SITE) {
				site(input, history, run);
				return;
			}
			plan = new KeywordPlan(ml.route() == MlFirstCall.Route.NOT_BOOK, ml.route() == MlFirstCall.Route.TITLE_LIKE,
					ml.route() == MlFirstCall.Route.TITLE_LIKE ? input.question() : "",
					ml.route() == MlFirstCall.Route.REFERS_SHOWN, ml.keywords(), List.of(), KeywordResult.OK);
		}
		else {
			plan = keywords(input.question(), history, run);
		}
		run.keywordResult = plan.result();
		run.titleLike = plan.titleLike();
		if (plan.titleLike() && !plan.title().isBlank()) {
			String title = plan.title().strip();
			// only words of the question go into the button, else the whole question (ADR-0153)
			String guess = input.question().contains(title) ? title : input.question();
			if (TitleMatch.close(guess, searchBooks.books())) {
				run.send(AiSearchEvents.TITLE_GUESS, new TitleGuess(WholeText.of(guess)));
			}
		}
		if (plan.notBook()) {
			finish(run, PickResult.NO_MATCH, "no_match");
			return;
		}

		boolean refers = plan.refersToShown() && !lastShown.isEmpty();
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
		Set<Long> excluded = new HashSet<>(refers ? Set.of() : shownBefore);
		excluded.addAll(chosen);
		boolean mix = nearest != null && !refers;
		List<Candidate> found = candidates.find(plan.keywords(), plan.core(), input.planLibraryIds(), excluded,
				mix ? Math.max(vectorSettings.keywordDepth(), max) : max - chosen.size());
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
			List<Long> vectorIds = vectorCandidates(nearest, known, excluded, input.planLibraryIds(), planCount,
					vectorSettings);
			run.vectorCount = vectorIds.size();
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
		if (chosen.isEmpty()) {
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
		Map<Long, String> tabCovers = covers.of(ids);
		Map<Long, String> tabBlurbs = blurbs.of(ids);
		picks(input, history, ids, choices, new Cards(known, listed, tabCovers, tabBlurbs, planCount), run);
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
			AiSearchProperties.Vector settings) {
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
		List<Long> keep = near.stream().filter(known::containsKey).filter(id -> !excluded.contains(id)).toList();
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

	/** The ML first call's plan and the vector candidates of its search vector. */
	private record MlStep(MlFirstCall.Plan plan, List<Long> nearest) {
	}

	/**
	 * The ML first step: the question's vector (and for a follow-up the earlier question's and the two joined, at the
	 * same time), the plan from them, the nearest books of the search vector. Null when a vector fails or comes later
	 * than {@code maxWait}: the search then asks Haiku.
	 */
	private MlStep mlStep(String question, String earlierQuestion, AiSearchProperties.Vector settings) {
		// the earlier question goes to the encoder too (this process or the embedding server, never logged)
		List<String> texts = earlierQuestion == null ? List.of(question)
				: List.of(question, earlierQuestion, earlierQuestion + " " + question);
		CompletableFuture<List<float[]>> lookup = vectors.vectors(texts);
		try {
			List<float[]> found = lookup.get(settings.maxWait().toMillis(), TimeUnit.MILLISECONDS);
			float[] vector = found.get(0);
			MlFirstCall.Plan plan = mlFirstCall.plan(question, vector, earlierQuestion,
					earlierQuestion == null ? null : found.get(1), earlierQuestion == null ? null : found.get(2));
			float[] search = plan.joinedVector() ? found.get(2) : vector;
			return new MlStep(plan, vectors.nearest(search, settings.vectorDepth() * 2));
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

	private void picks(Input input, List<ShownTurn> history, List<Long> ids, List<Choice> choices, Cards cards,
			Run run) {
		int maxPicks = properties.limits().picks();
		int maxChars = properties.limits().cardTextMaxChars();
		Set<Long> seen = new HashSet<>();
		long[] current = { -1 };
		long[] pending = { -1 };
		int[] currentChars = { 0 };
		PickStreamParser parser = new PickStreamParser(new PickStreamParser.Listener() {

			@Override
			public void headText(String text) {
				run.send(AiSearchEvents.HEAD_DELTA, new Text(text));
			}

			@Override
			public void pickNo(long no) {
				current[0] = -1;
				pending[0] = -1;
				if (run.shown.size() >= maxPicks || no < 1 || no > ids.size() || !seen.add(no)) {
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
			}

			@Override
			public void pickText(String text) {
				if (current[0] < 0 || currentChars[0] >= maxChars) {
					return;
				}
				int room = maxChars - currentChars[0];
				int length = text.codePointCount(0, text.length());
				String piece = length <= room ? text : text.substring(0, text.offsetByCodePoints(0, room));
				currentChars[0] += Math.min(length, room);
				run.send(AiSearchEvents.CARD_DELTA, new CardDelta(current[0], piece));
			}

			@Override
			public void pickEnd() {
				current[0] = -1;
				pending[0] = -1;
			}

		});
		LlmCall call = new LlmCall(Prompts.PICK_SYSTEM, Prompts.pickMessages(history, input.question(), choices),
				Prompts.PICK_SCHEMA, properties.pickMaxTokens());
		boolean failed = false;
		try {
			Result result = llm.stream(call, properties.pickTimeout(), piece -> {
				if (run.gone || run.sink.gone()) {
					run.gone = true;
					throw new ClientGone();
				}
				parser.feed(piece);
			});
			count(run, result.usage(), call.maxTokens());
			failed = !parser.complete();
		}
		catch (LlmException e) {
			if (e.failure() != Failure.NOT_CONFIGURED) {
				count(run, e.usage(), call.maxTokens());
			}
			failed = true;
		}
		if (run.gone) {
			run.pickResult = run.shown.isEmpty() ? PickResult.SKIPPED : PickResult.PARTIAL;
			return;
		}
		if (failed && run.shown.isEmpty()) {
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
			finish(run, PickResult.NO_MATCH, "no_match");
		}
		else {
			finish(run, PickResult.OK, "ok");
		}
	}

	/**
	 * What a card is made from: title and author of the search book; the list cover, else the tab cover; the list's AI
	 * summary, else the tab blurb (user 2026-10-25).
	 */
	private record Cards(Map<Long, SearchBook> known, Map<Long, ListedBook> listed, Map<Long, String> tabCovers,
			Map<Long, String> tabBlurbs, Map<Long, Integer> planCount) {

		Card card(int no, long bookId) {
			SearchBook book = known.get(bookId);
			ListedBook onList = listed.get(bookId);
			String cover = onList == null ? null : onList.coverPath();
			if (cover == null) {
				cover = tabCovers.get(bookId);
			}
			String summary = onList == null ? null : onList.aiSummary();
			if (summary == null || summary.isBlank()) {
				summary = tabBlurbs.get(bookId);
			}
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
