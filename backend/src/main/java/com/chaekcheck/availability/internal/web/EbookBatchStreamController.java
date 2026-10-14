package com.chaekcheck.availability.internal.web;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.Candidate;
import com.chaekcheck.availability.internal.search.ResultFilter;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * Next batch of a search (ADR-0014): the browser sends the candidates ({@code libraryId:page}, in order, at most
 * K); the server keeps no search state. Same events as the first stream without {@code targets}. A candidate whose
 * library is missing or disabled fails on its own with {@code library_not_found}.
 */
@RestController
class EbookBatchStreamController {

	private final BatchRunner batchRunner;

	private final UsageLog usageLog;

	EbookBatchStreamController(BatchRunner batchRunner, UsageLog usageLog) {
		this.batchRunner = batchRunner;
		this.usageLog = usageLog;
	}

	@GetMapping("/api/v1/ebook-searches/batches/stream")
	ResponseEntity<SseEmitter> stream(@RequestParam MultiValueMap<String, String> params,
			@RequestParam(required = false) String filter, @RequestParam(required = false) String batchNo,
			@RequestParam(required = false) String candidates, @RequestParam(required = false) String scope,
			@RequestParam(required = false) String visit, @RequestParam(required = false) String residenceRegionIds,
			@RequestParam(required = false) String workRegionIds, @RequestParam(required = false) String libraryIds,
			HttpServletRequest request) {
		UsageRequest usage = usageLog.capture(request);
		SearchQuery query = SearchConditionParam.parse(params);
		ResultFilter resultFilter = EbookSearchStreamController.parseFilter(filter);
		int batch = parseBatchNo(batchNo);
		List<CandidatesParam.Entry> entries = CandidatesParam.parse(candidates, batchRunner.maxCandidates());

		List<Candidate> found = new ArrayList<>();
		List<BatchRunner.MissingCandidate> missing = new ArrayList<>();
		for (CandidatesParam.Entry entry : entries) {
			batchRunner.target(entry.libraryId())
				.ifPresentOrElse(target -> found.add(new Candidate(target, entry.page())),
						() -> missing.add(new BatchRunner.MissingCandidate(entry.libraryId(), entry.page())));
		}

		SseEmitter emitter = EbookSearchStreamController.newEmitter(batchRunner);
		batchRunner.start(new SseEventSink(emitter), query, resultFilter, batch, found, missing, null, null);
		// Cycle 15: "more" after a direct search; scope, visit, regions and libraryIds are only for this row
		usageLog.recordMore(usage, UsageLog.MoreKind.DIRECT_NEXT_BATCH, batch, null, UsageConditions.more(query,
				resultFilter.value(), scope, visit, residenceRegionIds, workRegionIds, libraryIds));
		return EbookSearchStreamController.streamResponse(emitter);
	}

	static int parseBatchNo(String batchNo) {
		if (batchNo == null || batchNo.isBlank()) {
			throw new InvalidParamException("batchNo", "required", "batchNo is required");
		}
		try {
			int value = Integer.parseInt(batchNo.strip());
			if (value >= 2) {
				return value;
			}
		}
		catch (NumberFormatException ignored) {
			// falls through
		}
		throw new InvalidParamException("batchNo", "invalid_format", "batchNo must be an integer of 2 or more");
	}

}
