package com.chaekcheck.availability.internal.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.HoldingTask;
import com.chaekcheck.availability.internal.search.LibraryScope;
import com.chaekcheck.availability.internal.search.ResultFilter;
import com.chaekcheck.availability.internal.search.StreamEvents;
import com.chaekcheck.availability.internal.search.VisitFilter;
import com.chaekcheck.catalog.CatalogHoldings;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;

/**
 * First holding batch for books found with {@code /books} (ADR-0030, design-delta API): the plan libraries holding
 * the books as {@code targets}, then batch 1 over their detail pages. Everything is validated before the stream opens,
 * in the order bookIds, filter, scope, visit, libraryIds, regions, holdingLimit [안]: 400 validation_error, 422
 * invalid_region.
 * <p>
 * Cycle 10 (S14 "앞의 3곳만", user decision 2026-10-12): the optional {@code holdingLimit} (1 to 20) keeps only the
 * first N holdings in plan order, so only those libraries are asked; without it nothing changes.
 */
@RestController
class BookAvailabilityStreamController {

	/** Same as the {@code /books} page size [안]. */
	static final int MAX_BOOK_IDS = 20;

	/** Largest {@code holdingLimit}: the batch row limit [안]. */
	static final int MAX_HOLDING_LIMIT = 20;

	private final BatchRunner batchRunner;

	private final CatalogHoldings catalogHoldings;

	BookAvailabilityStreamController(BatchRunner batchRunner, CatalogHoldings catalogHoldings) {
		this.batchRunner = batchRunner;
		this.catalogHoldings = catalogHoldings;
	}

	@GetMapping("/api/v1/book-availabilities/stream")
	ResponseEntity<SseEmitter> stream(@RequestParam(required = false) String bookIds,
			@RequestParam(required = false) String residenceRegionIds,
			@RequestParam(required = false) String workRegionIds, @RequestParam(required = false) String filter,
			@RequestParam(required = false) String scope, @RequestParam(required = false) String visit,
			@RequestParam(required = false) String libraryIds, @RequestParam MultiValueMap<String, String> params) {
		List<Long> books = IdListParam.parse("bookIds", bookIds, MAX_BOOK_IDS);
		ResultFilter resultFilter = EbookSearchStreamController.parseFilter(filter);
		LibraryScope libraryScope = EbookSearchStreamController.parseScope(scope);
		VisitFilter visitFilter = EbookSearchStreamController.parseVisit(visit);
		Set<Long> chosen = LibraryIdsParam.parse(libraryIds);
		Set<Long> residence = RegionIdsParam.parse("residenceRegionIds", residenceRegionIds);
		Set<Long> work = RegionIdsParam.parse("workRegionIds", workRegionIds);
		Integer holdingLimit = parseHoldingLimit(params.get("holdingLimit"));
		List<SearchTarget> plan = batchRunner.plan(residence, work,
				new PlanOptions(libraryScope.option(), visitFilter.option(), chosen));

		List<HoldingTask> tasks = tasks(books, plan,
				catalogHoldings.holdingsOf(books, plan.stream().map(SearchTarget::id).toList()));
		if (holdingLimit != null) {
			tasks = firstInPlanOrder(tasks, plan, holdingLimit);
		}
		Map<Long, SearchTarget> used = new LinkedHashMap<>();
		tasks.forEach(task -> used.putIfAbsent(task.target().id(), task.target()));
		List<StreamEvents.Library> libraries = plan.stream()
			.filter(target -> used.containsKey(target.id()))
			.map(StreamEvents.Library::from)
			.toList();
		StreamEvents.HoldingTargets targets = new StreamEvents.HoldingTargets(resultFilter.value(),
				libraryScope.value(), visitFilter.value(), !residence.isEmpty() || !work.isEmpty(), libraries,
				tasks.stream()
					.map(task -> new StreamEvents.HoldingEntry(task.holding().holdingId(), task.holding().bookId(),
							task.holding().libraryId()))
					.toList());

		SseEmitter emitter = EbookSearchStreamController.newEmitter(batchRunner);
		batchRunner.startHoldings(new SseEventSink(emitter), resultFilter, 1, tasks, List.of(), targets);
		return EbookSearchStreamController.streamResponse(emitter);
	}

	/**
	 * {@code holdingLimit}: missing means no limit; one integer from 1 to 20, else 400 {@code invalid_format};
	 * given twice, 400 {@code duplicate}. Read from all values: Spring would join repeats with commas.
	 */
	static Integer parseHoldingLimit(List<String> values) {
		if (values == null) {
			return null;
		}
		List<String> given = values.stream().filter(value -> value != null && !value.isBlank()).toList();
		if (given.isEmpty()) {
			return null;
		}
		if (given.size() > 1) {
			throw new InvalidParamException("holdingLimit", "duplicate", "holdingLimit may appear only once");
		}
		String raw = given.getFirst().strip();
		if (raw.matches("[1-9][0-9]?") && Integer.parseInt(raw) <= MAX_HOLDING_LIMIT) {
			return Integer.parseInt(raw);
		}
		throw new InvalidParamException("holdingLimit", "invalid_format",
				"holdingLimit must be an integer from 1 to " + MAX_HOLDING_LIMIT);
	}

	/**
	 * The first {@code limit} holdings by plan order (then book order, then holding id), kept in the usual order. With
	 * one book and one holding per library (S14) these are the first N plan libraries holding it [안].
	 */
	static List<HoldingTask> firstInPlanOrder(List<HoldingTask> tasks, List<SearchTarget> plan, int limit) {
		if (tasks.size() <= limit) {
			return tasks;
		}
		Map<Long, Integer> planOrder = new HashMap<>();
		for (int i = 0; i < plan.size(); i++) {
			planOrder.put(plan.get(i).id(), i);
		}
		List<Integer> positions = new ArrayList<>();
		for (int i = 0; i < tasks.size(); i++) {
			positions.add(i);
		}
		Set<Integer> kept = Set.copyOf(positions.stream()
			.sorted(Comparator.comparingInt((Integer i) -> planOrder.get(tasks.get(i).target().id()))
				.thenComparingInt(i -> i))
			.limit(limit)
			.toList());
		List<HoldingTask> limited = new ArrayList<>(limit);
		for (int i = 0; i < tasks.size(); i++) {
			if (kept.contains(i)) {
				limited.add(tasks.get(i));
			}
		}
		return limited;
	}

	/**
	 * Holdings of plan libraries only, in {@code bookIds} order and inside a book in plan order [안] (the library
	 * order of ADR-0027).
	 */
	static List<HoldingTask> tasks(List<Long> bookIds, List<SearchTarget> plan, List<HoldingRef> holdings) {
		Map<Long, Integer> planOrder = new HashMap<>();
		Map<Long, SearchTarget> targets = new HashMap<>();
		for (int i = 0; i < plan.size(); i++) {
			planOrder.put(plan.get(i).id(), i);
			targets.put(plan.get(i).id(), plan.get(i));
		}
		Map<Long, List<HoldingRef>> byBook = new HashMap<>();
		for (HoldingRef holding : holdings) {
			if (targets.containsKey(holding.libraryId())) {
				byBook.computeIfAbsent(holding.bookId(), id -> new ArrayList<>()).add(holding);
			}
		}
		List<HoldingTask> tasks = new ArrayList<>();
		for (long bookId : bookIds) {
			byBook.getOrDefault(bookId, List.of())
				.stream()
				.sorted(Comparator.comparingInt((HoldingRef h) -> planOrder.get(h.libraryId()))
					.thenComparingLong(HoldingRef::holdingId))
				.forEach(holding -> tasks.add(HoldingTask.of(targets.get(holding.libraryId()), holding)));
		}
		return tasks;
	}

}
