package com.chaekcheck.availability.internal.web;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.Candidate;
import com.chaekcheck.availability.internal.search.LibraryScope;
import com.chaekcheck.availability.internal.search.ResultFilter;
import com.chaekcheck.availability.internal.search.StreamEvents;
import com.chaekcheck.availability.internal.search.VisitFilter;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;

/**
 * First batch of a search (ADR-0014, design-delta "API"): the whole plan as {@code targets}, then batch 1 in plan
 * order. Everything is validated before the stream opens, in the order condition, filter, scope, visit, regions, so bad
 * input gets an ordinary Problem Details response: 400 invalid_query, 400 validation_error, 422 invalid_region. No
 * {@code produces} condition: the same request must be able to get {@code application/problem+json}.
 */
@RestController
class EbookSearchStreamController {

	/** Slack after the last possible event for the final events to be written. */
	static final Duration EMITTER_GRACE = Duration.ofSeconds(5);

	private final BatchRunner batchRunner;

	EbookSearchStreamController(BatchRunner batchRunner) {
		this.batchRunner = batchRunner;
	}

	@GetMapping("/api/v1/ebook-searches/stream")
	ResponseEntity<SseEmitter> stream(@RequestParam MultiValueMap<String, String> params,
			@RequestParam(required = false) String residenceRegionIds,
			@RequestParam(required = false) String workRegionIds, @RequestParam(required = false) String filter,
			@RequestParam(required = false) String scope, @RequestParam(required = false) String visit,
			@RequestParam(required = false) String libraryIds) {
		SearchQuery query = SearchConditionParam.parse(params);
		ResultFilter resultFilter = parseFilter(filter);
		LibraryScope libraryScope = parseScope(scope);
		VisitFilter visitFilter = parseVisit(visit);
		Set<Long> chosen = LibraryIdsParam.parse(libraryIds);
		Set<Long> residence = RegionIdsParam.parse("residenceRegionIds", residenceRegionIds);
		Set<Long> work = RegionIdsParam.parse("workRegionIds", workRegionIds);
		List<SearchTarget> plan = batchRunner.plan(residence, work,
				new PlanOptions(libraryScope.option(), visitFilter.option(), chosen));

		SseEmitter emitter = newEmitter(batchRunner);
		StreamEvents.Targets targets = StreamEvents.Targets.of(query, resultFilter.value(), libraryScope.value(),
				visitFilter.value(), !residence.isEmpty() || !work.isEmpty(),
				plan.stream().map(StreamEvents.Library::from).toList());
		batchRunner.start(new SseEventSink(emitter), query, resultFilter, 1,
				plan.stream().map(target -> new Candidate(target, 1)).toList(), List.of(), targets);
		return streamResponse(emitter);
	}

	static ResultFilter parseFilter(String filter) {
		ResultFilter parsed = ResultFilter.parse(filter);
		if (parsed == null) {
			throw new InvalidParamException("filter", "invalid_format", "filter must be all or available");
		}
		return parsed;
	}

	static LibraryScope parseScope(String scope) {
		LibraryScope parsed = LibraryScope.parse(scope);
		if (parsed == null) {
			throw new InvalidParamException("scope", "invalid_format", "scope must be all or joinable");
		}
		return parsed;
	}

	static VisitFilter parseVisit(String visit) {
		VisitFilter parsed = VisitFilter.parse(visit);
		if (parsed == null) {
			throw new InvalidParamException("visit", "invalid_format", "visit must be any or none");
		}
		return parsed;
	}

	static SseEmitter newEmitter(BatchRunner batchRunner) {
		return new SseEmitter(batchRunner.streamDuration().plus(EMITTER_GRACE).toMillis());
	}

	static ResponseEntity<SseEmitter> streamResponse(SseEmitter emitter) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noCache())
			// ask reverse proxies (nginx) not to buffer the stream
			.header("X-Accel-Buffering", "no")
			.body(emitter);
	}

}
