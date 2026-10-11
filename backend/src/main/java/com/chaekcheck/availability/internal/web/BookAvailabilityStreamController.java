package com.chaekcheck.availability.internal.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
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
 * in the order bookIds, filter, scope, visit, libraryIds, regions [안]: 400 validation_error, 422 invalid_region.
 */
@RestController
class BookAvailabilityStreamController {

	/** Same as the {@code /books} page size [안]. */
	static final int MAX_BOOK_IDS = 20;

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
			@RequestParam(required = false) String libraryIds) {
		List<Long> books = IdListParam.parse("bookIds", bookIds, MAX_BOOK_IDS);
		ResultFilter resultFilter = EbookSearchStreamController.parseFilter(filter);
		LibraryScope libraryScope = EbookSearchStreamController.parseScope(scope);
		VisitFilter visitFilter = EbookSearchStreamController.parseVisit(visit);
		Set<Long> chosen = LibraryIdsParam.parse(libraryIds);
		Set<Long> residence = RegionIdsParam.parse("residenceRegionIds", residenceRegionIds);
		Set<Long> work = RegionIdsParam.parse("workRegionIds", workRegionIds);
		List<SearchTarget> plan = batchRunner.plan(residence, work,
				new PlanOptions(libraryScope.option(), visitFilter.option(), chosen));

		List<HoldingTask> tasks = tasks(books, plan,
				catalogHoldings.holdingsOf(books, plan.stream().map(SearchTarget::id).toList()));
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
