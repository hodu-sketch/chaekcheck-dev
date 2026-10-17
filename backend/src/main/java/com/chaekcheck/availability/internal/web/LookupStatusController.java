package com.chaekcheck.availability.internal.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.availability.internal.status.LookupStatus;
import com.chaekcheck.availability.internal.status.LookupStatusService;

/**
 * {@code GET /api/v1/lookup-status} (cycle 20, ADR-0118, design-delta "API"): the status signal the header dot
 * shows. Reads memory only, never a library or the database, so it answers 200 while the database is down (the
 * level is then GRAY). Open without a login ({@code SecurityConfiguration}: every path outside {@code /api/v1/me}
 * is permitAll) and outside nginx's library-call zone (the general {@code /api/} location). {@code Cache-Control:
 * no-cache} so a browser asks the server each time (the server memo is 1s).
 */
@RestController
class LookupStatusController {

	private final LookupStatusService statusService;

	LookupStatusController(LookupStatusService statusService) {
		this.statusService = statusService;
	}

	@GetMapping("/api/v1/lookup-status")
	ResponseEntity<LookupStatus> status() {
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(statusService.current());
	}

}
