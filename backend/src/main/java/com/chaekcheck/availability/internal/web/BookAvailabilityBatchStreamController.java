package com.chaekcheck.availability.internal.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.HoldingTask;
import com.chaekcheck.availability.internal.search.ResultFilter;
import com.chaekcheck.catalog.CatalogHoldings;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.SearchTarget;

/**
 * Next holding batch (ADR-0014, ADR-0030): the browser sends the holdings still to ask ({@code candidates}, holding
 * ids in order, at most K); the server keeps no state. Same events as the first stream without {@code targets}. A
 * holding that does not exist or whose library is missing or disabled fails on its own with
 * {@code library_not_found}.
 */
@RestController
class BookAvailabilityBatchStreamController {

	private final BatchRunner batchRunner;

	private final CatalogHoldings catalogHoldings;

	BookAvailabilityBatchStreamController(BatchRunner batchRunner, CatalogHoldings catalogHoldings) {
		this.batchRunner = batchRunner;
		this.catalogHoldings = catalogHoldings;
	}

	@GetMapping("/api/v1/book-availabilities/batches/stream")
	ResponseEntity<SseEmitter> stream(@RequestParam(required = false) String filter,
			@RequestParam(required = false) String batchNo, @RequestParam(required = false) String candidates) {
		ResultFilter resultFilter = EbookSearchStreamController.parseFilter(filter);
		int batch = EbookBatchStreamController.parseBatchNo(batchNo);
		List<Long> ids = IdListParam.parse("candidates", candidates, batchRunner.maxCandidates());

		Map<Long, HoldingRef> found = new HashMap<>();
		catalogHoldings.holdings(ids).forEach(holding -> found.put(holding.holdingId(), holding));
		Map<Long, Optional<SearchTarget>> libraries = new HashMap<>();
		List<HoldingTask> tasks = new ArrayList<>();
		List<Long> missing = new ArrayList<>();
		for (long id : ids) {
			HoldingRef holding = found.get(id);
			Optional<SearchTarget> target = holding == null ? Optional.empty()
					: libraries.computeIfAbsent(holding.libraryId(), batchRunner::target);
			if (target.isPresent()) {
				tasks.add(HoldingTask.of(target.get(), holding));
			}
			else {
				missing.add(id);
			}
		}

		SseEmitter emitter = EbookSearchStreamController.newEmitter(batchRunner);
		batchRunner.startHoldings(new SseEventSink(emitter), resultFilter, batch, tasks, missing, null);
		return EbookSearchStreamController.streamResponse(emitter);
	}

}
