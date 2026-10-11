package com.chaekcheck.library.internal.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryEligibility;

/**
 * {@code GET /api/v1/libraries}: every enabled service library with the eligibility for the regions sent (ADR-0049).
 * Public and without login: it knows no member, the web sends the regions. The "내 도서관" screen builds its sections
 * from it (auto by residence or work from {@code matchedBy}, open to anyone from {@code OPEN}, names of added
 * libraries, name search). In id order, no paging.
 * <p>
 * A library whose region rules match none of the regions sent is {@code UNKNOWN} here, not left out. That differs
 * from the search APIs, where {@code UNKNOWN} means "no regions sent" and such a library is not in the plan.
 * <p>
 * Names are the body's name only. {@code vendor}, {@code lendingModel} (null for the integrated portals) and
 * {@code orderNo} tell sites of one body apart; the web turns them into "교보 · 소장" (ADR-0071, ADR-0076).
 */
@RestController
class LibraryListController {

	static final String RESIDENCE = "residenceRegionIds";

	static final String WORK = "workRegionIds";

	private final LibraryCatalog catalog;

	LibraryListController(LibraryCatalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping("/api/v1/libraries")
	Libraries libraries(@RequestParam(name = RESIDENCE, required = false) String residence,
			@RequestParam(name = WORK, required = false) String work) {
		return new Libraries(catalog.eligibilityFor(RegionIdsParam.parse(RESIDENCE, residence),
				RegionIdsParam.parse(WORK, work)));
	}

	record Libraries(List<LibraryEligibility> libraries) {
	}

}
