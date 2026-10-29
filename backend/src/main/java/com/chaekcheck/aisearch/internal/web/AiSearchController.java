package com.chaekcheck.aisearch.internal.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.flow.AiSearchFlow;
import com.chaekcheck.aisearch.internal.flow.AiSearchSink;
import com.chaekcheck.aisearch.internal.match.CandidateQuery;
import com.chaekcheck.aisearch.internal.quota.AiSearchQuota;
import com.chaekcheck.aisearch.internal.quota.DailyBudget;
import com.chaekcheck.aisearch.internal.web.AiSearchErrors.BusyException;
import com.chaekcheck.aisearch.internal.web.AiSearchErrors.InvalidQueryException;
import com.chaekcheck.aisearch.internal.web.AiSearchErrors.InvalidRequestException;
import com.chaekcheck.aisearch.internal.web.AiSearchErrors.OffException;
import com.chaekcheck.aisearch.internal.web.AiSearchErrors.PausedException;
import com.chaekcheck.aisearch.internal.web.AiSearchErrors.RateLimitedException;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * The AI search (cycle 24 handoff step 8-3, design-delta "API"). {@code POST /api/v1/ai-searches/stream}: the question
 * is in the body only (never in the address, ADR-0152). Everything is checked before the stream opens, so a refusal
 * is an ordinary Problem Details answer (the stream convention of step 0 #3, no {@code produces}): off 404, body 400
 * {@code validation_error}, question 400 {@code invalid_query}, regions 422 {@code invalid_region}, the IP's limits 429
 * {@code too_many_requests} + {@code Retry-After}, the day's cost 503 {@code ai_search_paused}, every AI slot taken 503
 * {@code server_busy} + {@code Retry-After}. Then the search runs on a virtual thread and streams. {@code GET
 * /api/v1/ai-searches/status}: 200 {@code {}} when on, 404 when off (step 0 #9). With login on, the POST needs the CSRF
 * header like {@code POST /api/v1/usage-events} (step 0 #5). The question never reaches a log or an error message.
 * {@code POST /api/v1/ai-searches/library-counts}: how many of the plan's libraries hold each book the conversation
 * already showed, so the cards follow a region changed after the answer (user 2026-10-25: "이미 검색된 도서에 한해서");
 * no AI call, so neither the IP's limits nor the day's cost count it.
 */
@RestController
class AiSearchController {

	/**
	 * A body larger than this is not a question (design-delta sizes: 200 + 9 earlier turns of question, head, ask-back
	 * and choices, about 1,600 characters each at most, and ids; user 2026-10-29 「대화 기억」 10 questions) [안].
	 */
	static final int MAX_BODY_CHARS = 32 * 1024;

	/** An earlier ask-back's choices at most (the pick call sends 2 to 4). */
	private static final int MAX_ASK_CHOICES = 4;

	/** An earlier answer's head kept at most, in characters (the pick call writes one or two sentences) [안]. */
	static final int HEAD_MAX_CHARS = 400;

	/** Books one count request may ask about: every card of a full conversation (20 turns of at most 8). */
	static final int MAX_COUNT_BOOKS = 160;

	/** Slack after the last possible event for the final events to be written. */
	static final Duration EMITTER_GRACE = Duration.ofSeconds(10);

	private final AiSearchProperties properties;

	private final AiSearchFlow flow;

	private final AiSearchQuota quota;

	private final DailyBudget budget;

	private final AiSearchSlots slots;

	private final LibraryCatalog libraries;

	private final CandidateQuery candidates;

	private final UsageLog usageLog;

	private final JsonMapper json;

	private final ExecutorService executor;

	AiSearchController(AiSearchProperties properties, AiSearchFlow flow, AiSearchQuota quota, DailyBudget budget,
			AiSearchSlots slots, LibraryCatalog libraries, CandidateQuery candidates, UsageLog usageLog, JsonMapper json,
			@Qualifier("aiSearchExecutor") ExecutorService executor) {
		this.properties = properties;
		this.flow = flow;
		this.quota = quota;
		this.budget = budget;
		this.slots = slots;
		this.libraries = libraries;
		this.candidates = candidates;
		this.usageLog = usageLog;
		this.json = json;
		this.executor = executor;
	}

	@GetMapping("/api/v1/ai-searches/status")
	ResponseEntity<Map<String, Object>> status() {
		if (!properties.enabled()) {
			throw new OffException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(Map.of());
	}

	@PostMapping("/api/v1/ai-searches/library-counts")
	ResponseEntity<Map<String, Object>> libraryCounts(HttpServletRequest request) {
		if (!properties.enabled()) {
			throw new OffException();
		}
		LibraryCountsRequest parsed = read(request, LibraryCountsRequest.class);
		Set<Long> bookIds = ids("bookIds", parsed.bookIds());
		if (bookIds.size() > MAX_COUNT_BOOKS) {
			throw new InvalidRequestException("bookIds", "too_many", "bookIds holds at most " + MAX_COUNT_BOOKS + " ids");
		}
		Set<Long> residence = ids("residenceRegionIds", parsed.residenceRegionIds());
		Set<Long> work = ids("workRegionIds", parsed.workRegionIds());
		Set<Long> chosen = ids("libraryIds", parsed.libraryIds());
		PlanOptions options = new PlanOptions(scope(parsed.scope()), visit(parsed.visit()), chosen);
		Set<Long> planLibraries = new LinkedHashSet<>();
		libraries.planSearch(residence, work, options).forEach(target -> planLibraries.add(target.id()));
		Map<Long, Integer> found = candidates.planCounts(bookIds, planLibraries);
		Map<Long, Integer> counts = new LinkedHashMap<>();
		bookIds.forEach(id -> counts.put(id, found.getOrDefault(id, 0)));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("counts", counts));
	}

	@PostMapping("/api/v1/ai-searches/stream")
	ResponseEntity<SseEmitter> stream(HttpServletRequest request) {
		long started = System.nanoTime();
		if (!properties.enabled()) {
			throw new OffException();
		}
		// the IP's limits first, before any work for the request (step 8 security review); a refused or wrong request
		// is counted too
		AiSearchQuota.Decision decision = quota.tryAcquire(ClientIp.quotaKey(ClientIp.of(request)));
		if (!decision.allowed()) {
			budget.countRateLimited();
			throw new RateLimitedException(decision.retryAfterSeconds());
		}
		UsageRequest usage = usageLog.capture(request);
		AiSearchRequest parsed = read(request, AiSearchRequest.class);
		String question = question(parsed.question());
		int turnNo = turnNo(parsed.turnNo());
		List<AiSearchFlow.Turn> history = history(parsed.history());
		Set<Long> residence = ids("residenceRegionIds", parsed.residenceRegionIds());
		Set<Long> work = ids("workRegionIds", parsed.workRegionIds());
		Set<Long> chosen = ids("libraryIds", parsed.libraryIds());
		PlanOptions options = new PlanOptions(scope(parsed.scope()), visit(parsed.visit()), chosen);
		List<SearchTarget> plan = libraries.planSearch(residence, work, options);

		if (!budget.allowsStart()) {
			budget.countPaused();
			throw new PausedException();
		}
		if (!slots.tryAcquire()) {
			throw new BusyException();
		}
		AiSearchFlow.Input input;
		SseEmitter emitter;
		try {
			budget.countRequest();
			Set<Long> planLibraries = new LinkedHashSet<>();
			plan.forEach(target -> planLibraries.add(target.id()));
			input = new AiSearchFlow.Input(question, turnNo, history, planLibraries, usage, started);
			emitter = new SseEmitter(
					properties.keywordTimeout().plus(properties.pickTimeout()).plus(EMITTER_GRACE).toMillis());
			AiSearchSink sink = new EmitterSink(emitter);
			executor.execute(() -> {
				try {
					flow.run(input, sink);
				}
				finally {
					slots.release();
				}
			});
		}
		catch (RuntimeException e) {
			slots.release();
			if (e instanceof RejectedExecutionException) {
				throw new BusyException();
			}
			throw e;
		}
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache())
			// ask reverse proxies (nginx) not to buffer the stream
			.header("X-Accel-Buffering", "no")
			.body(emitter);
	}

	/** Reads at most {@value #MAX_BODY_CHARS} bytes, whether or not the request says its length (chunked bodies too). */
	private <T> T read(HttpServletRequest request, Class<T> type) {
		byte[] bytes;
		try {
			bytes = request.getContentLengthLong() > MAX_BODY_CHARS ? null
					: request.getInputStream().readNBytes(MAX_BODY_CHARS + 1);
		}
		catch (java.io.IOException e) {
			throw new InvalidRequestException("body", "invalid_format", "the body could not be read");
		}
		if (bytes == null || bytes.length == 0 || bytes.length > MAX_BODY_CHARS) {
			throw new InvalidRequestException("body", "invalid_format", "a JSON object of at most 16KB is needed");
		}
		String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
		try {
			T parsed = json.readValue(body, type);
			if (parsed == null) {
				throw new InvalidRequestException("body", "invalid_format", "a JSON object is needed");
			}
			return parsed;
		}
		catch (JacksonException e) {
			// the parser's message may quote the body: not passed on
			throw new InvalidRequestException("body", "invalid_format", "the body is not the expected JSON object");
		}
	}

	private String question(String raw) {
		if (raw == null) {
			throw new InvalidRequestException("question", "required", "question is required");
		}
		String question = com.chaekcheck.aisearch.internal.flow.WholeText.of(raw.strip());
		int length = question.codePointCount(0, question.length());
		if (length < 2 || length > properties.limits().questionMaxLength()) {
			throw new InvalidQueryException();
		}
		return question;
	}

	private static int turnNo(Integer turnNo) {
		if (turnNo == null || turnNo < 1 || turnNo > 20) {
			throw new InvalidRequestException("turnNo", "out_of_range", "turnNo must be 1 to 20");
		}
		return turnNo;
	}

	private List<AiSearchFlow.Turn> history(List<AiSearchRequest.HistoryTurn> raw) {
		if (raw == null) {
			return List.of();
		}
		if (raw.size() > properties.limits().historyTurns()) {
			throw new InvalidRequestException("history", "too_many",
					"history holds at most " + properties.limits().historyTurns() + " turns");
		}
		List<AiSearchFlow.Turn> turns = new ArrayList<>();
		for (AiSearchRequest.HistoryTurn turn : raw) {
			String question = turn == null || turn.question() == null ? "" : turn.question().strip();
			int length = question.codePointCount(0, question.length());
			if (length < 1 || length > properties.limits().questionMaxLength()) {
				throw new InvalidRequestException("history.question", "invalid_length",
						"each earlier question must be 1 to " + properties.limits().questionMaxLength() + " characters");
			}
			List<Long> bookIds = turn.bookIds() == null ? List.of() : turn.bookIds();
			if (bookIds.size() > properties.limits().picks() || bookIds.stream().anyMatch(id -> id == null)) {
				throw new InvalidRequestException("history.bookIds", "invalid_format",
						"each earlier turn has at most " + properties.limits().picks() + " book ids");
			}
			String asked = turn.askQuestion() == null ? null : turn.askQuestion().strip();
			List<String> choices = turn.askChoices() == null ? List.of() : turn.askChoices();
			if (asked != null && (asked.codePointCount(0, asked.length()) > properties.limits().questionMaxLength()
					|| choices.size() > MAX_ASK_CHOICES || choices.stream()
						.anyMatch(c -> c == null || c.codePointCount(0, c.length()) > properties.limits().questionMaxLength()))) {
				throw new InvalidRequestException("history.askQuestion", "invalid_format",
						"an earlier ask-back is one question and at most " + MAX_ASK_CHOICES + " choices");
			}
			// an earlier head longer than this is cut, not refused: it was the model's own text (user 2026-10-29)
			String head = turn.head() == null ? null : turn.head().strip();
			if (head != null && head.codePointCount(0, head.length()) > HEAD_MAX_CHARS) {
				head = head.substring(0, head.offsetByCodePoints(0, HEAD_MAX_CHARS));
			}
			turns.add(new AiSearchFlow.Turn(question, bookIds, asked == null || asked.isEmpty() ? null : asked,
					asked == null || asked.isEmpty() ? List.of() : choices.stream().map(String::strip).toList(),
					head == null || head.isEmpty() ? null : head));
		}
		return turns;
	}

	private static Set<Long> ids(String field, List<Long> raw) {
		if (raw == null) {
			return Set.of();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (Long id : raw) {
			if (id == null || id < 1) {
				throw new InvalidRequestException(field, "invalid_format", field + " must hold positive ids");
			}
			ids.add(id);
		}
		return ids;
	}

	private static PlanOptions.Scope scope(String scope) {
		if (scope == null || scope.equals("all")) {
			return PlanOptions.Scope.ALL;
		}
		if (scope.equals("joinable")) {
			return PlanOptions.Scope.JOINABLE;
		}
		throw new InvalidRequestException("scope", "invalid_format", "scope must be all or joinable");
	}

	private static PlanOptions.Visit visit(String visit) {
		if (visit == null || visit.equals("any")) {
			return PlanOptions.Visit.ANY;
		}
		if (visit.equals("none")) {
			return PlanOptions.Visit.NONE;
		}
		throw new InvalidRequestException("visit", "invalid_format", "visit must be any or none");
	}

	/** Writes the events as SSE; the emitter's error and timeout tell that the client is gone. */
	private static final class EmitterSink implements AiSearchSink {

		private final SseEmitter emitter;

		private final AtomicBoolean gone = new AtomicBoolean();

		EmitterSink(SseEmitter emitter) {
			this.emitter = emitter;
			emitter.onError(ex -> gone.set(true));
			emitter.onTimeout(() -> gone.set(true));
		}

		@Override
		public boolean send(String event, Object data) {
			if (gone.get()) {
				return false;
			}
			try {
				emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
				return true;
			}
			catch (java.io.IOException | IllegalStateException ex) {
				gone.set(true);
				return false;
			}
		}

		@Override
		public boolean gone() {
			return gone.get();
		}

		@Override
		public void complete() {
			try {
				emitter.complete();
			}
			catch (IllegalStateException ex) {
				// already completed after a timeout
			}
		}

	}

}
