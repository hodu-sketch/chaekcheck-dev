package com.chaekcheck.aisearch.internal.flow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
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
import com.chaekcheck.aisearch.internal.match.PlanTargets;
import com.chaekcheck.aisearch.internal.match.PlanTargets.Target;
import com.chaekcheck.aisearch.internal.quota.DailyBudget;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageLog.AiSearchRecord;
import com.chaekcheck.usagelog.UsageLog.KeywordResult;
import com.chaekcheck.usagelog.UsageLog.PickResult;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * One AI search after the checks (cycle 24 handoff step 8-2, design-delta "주요 흐름"): the first call turns the question
 * into keywords (or the question's own words when it fails, spec R6-1), the keyword match picks at most 30 listed
 * books the plan libraries hold, the second call streams the head, then each pick's number and text. A pick's card
 * goes out only after its number is checked (in range, not seen, at most 8; ADR-0153): a refused number sends neither
 * card nor text. A card's text stops at 300 characters. The second call failing before any card sends the first 8
 * books without text ({@code no_text}); failing after one sends {@code partial}. The day's cost and one usage row are
 * written at the end. Neither the question nor the history is logged.
 */
@Component
public class AiSearchFlow {

	private static final Logger log = LoggerFactory.getLogger(AiSearchFlow.class);

	private final LlmClient llm;

	private final CandidateQuery candidates;

	private final DailyBudget budget;

	private final ListedBooks listedBooks;

	private final UsageLog usageLog;

	private final AiSearchProperties properties;

	private final JsonMapper json;

	AiSearchFlow(LlmClient llm, CandidateQuery candidates, DailyBudget budget, ListedBooks listedBooks,
			UsageLog usageLog, AiSearchProperties properties, JsonMapper json) {
		this.llm = llm;
		this.candidates = candidates;
		this.budget = budget;
		this.listedBooks = listedBooks;
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
			KeywordResult result) {
	}

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
				log.info("ai search: keyword {}, pick {}, candidates {}, cards {}, {} ms, tokens {}/{}{}",
						run.keywordResult, run.pickResult, run.candidateCount, run.shown.size(), elapsedMs,
						run.inputTokens, run.outputTokens, run.gone ? ", client gone" : "");
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
		Map<Long, ListedBook> listed = new HashMap<>();
		List<ListedBook> books = listedBooks.current().books();
		for (ListedBook book : books) {
			listed.put(book.bookId(), book);
		}
		List<ShownTurn> history = new ArrayList<>();
		Set<Long> shownBefore = new HashSet<>();
		List<Long> lastShown = List.of();
		for (Turn turn : input.history()) {
			List<Long> known = turn.bookIds()
				.stream()
				.filter(listed::containsKey)
				.distinct()
				.limit(properties.limits().picks())
				.toList();
			history.add(new ShownTurn(turn.question(), known.stream().map(id -> listed.get(id).title()).toList()));
			shownBefore.addAll(known);
			lastShown = known;
		}

		KeywordPlan plan = keywords(input.question(), history, run);
		run.keywordResult = plan.result();
		run.titleLike = plan.titleLike();
		if (plan.titleLike() && !plan.title().isBlank()) {
			String title = plan.title().strip();
			// only words of the question go into the button, else the whole question (ADR-0153)
			run.send(AiSearchEvents.TITLE_GUESS,
					new TitleGuess(WholeText.of(input.question().contains(title) ? title : input.question())));
		}
		if (plan.notBook()) {
			finish(run, PickResult.NO_MATCH, "no_match");
			return;
		}

		boolean refers = plan.refersToShown() && !lastShown.isEmpty();
		List<Target> targets = PlanTargets.of(books, input.planLibraryIds(), refers ? Set.of() : shownBefore);
		Map<Long, Integer> planCount = new HashMap<>();
		for (Target target : targets) {
			planCount.put(target.bookId(), target.planCount());
		}
		int max = properties.limits().candidates();
		LinkedHashSet<Long> chosen = new LinkedHashSet<>();
		if (refers) {
			lastShown.stream().filter(planCount::containsKey).limit(max).forEach(chosen::add);
		}
		List<Target> rest = targets.stream().filter(target -> !chosen.contains(target.bookId())).toList();
		for (Candidate candidate : candidates.find(plan.keywords(), rest, max - chosen.size())) {
			chosen.add(candidate.bookId());
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
		List<Choice> choices = ids.stream()
			.map(id -> new Choice(listed.get(id).title(), listed.get(id).author(), lines.get(id)))
			.toList();
		run.candidateCount = ids.size();
		picks(input, history, ids, choices, listed, planCount, run);
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
			if (!answer.path("notBook").isBoolean() || !answer.path("keywords").isArray()) {
				throw new IllegalStateException("the keyword answer has not the schema's fields");
			}
			return new KeywordPlan(answer.path("notBook").asBoolean(), answer.path("titleLike").asBoolean(false),
					answer.path("title").asString(""), answer.path("refersToShown").asBoolean(false), keywords,
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
		return new KeywordPlan(false, false, "", false, List.of(question.split("\\s+")), failed);
	}

	private void picks(Input input, List<ShownTurn> history, List<Long> ids, List<Choice> choices,
			Map<Long, ListedBook> listed, Map<Long, Integer> planCount, Run run) {
		int maxPicks = properties.limits().picks();
		int maxChars = properties.limits().cardTextMaxChars();
		Set<Long> seen = new HashSet<>();
		long[] current = { -1 };
		int[] currentChars = { 0 };
		PickStreamParser parser = new PickStreamParser(new PickStreamParser.Listener() {

			@Override
			public void headText(String text) {
				run.send(AiSearchEvents.HEAD_DELTA, new Text(text));
			}

			@Override
			public void pickNo(long no) {
				current[0] = -1;
				if (run.shown.size() >= maxPicks || no < 1 || no > ids.size() || !seen.add(no)) {
					return;
				}
				long bookId = ids.get((int) no - 1);
				ListedBook book = listed.get(bookId);
				run.shown.add(bookId);
				run.send(AiSearchEvents.CARD, new Card(run.shown.size(), bookId, book.title(), book.author(),
						book.coverPath(), planCount.getOrDefault(bookId, 0)));
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
				ListedBook book = listed.get(bookId);
				run.shown.add(bookId);
				run.send(AiSearchEvents.CARD, new Card(run.shown.size(), bookId, book.title(), book.author(),
						book.coverPath(), planCount.getOrDefault(bookId, 0)));
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

	private void count(Run run, Usage usage, int maxTokens) {
		run.inputTokens += usage.inputTokens();
		run.outputTokens += budget.addCall(usage, maxTokens);
	}

	private static void finish(Run run, PickResult result, String doneResult) {
		run.pickResult = result;
		run.send(AiSearchEvents.DONE, new Done(doneResult, run.shown));
	}

}
