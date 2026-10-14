package com.chaekcheck.sitecontent.internal.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.sitecontent.internal.Today;
import com.chaekcheck.sitecontent.internal.VersionDate;
import com.chaekcheck.sitecontent.internal.notice.NoticeStore;
import com.chaekcheck.sitecontent.internal.web.SitecontentErrors.NotFoundException;
import com.chaekcheck.sitecontent.internal.web.SitecontentResponses.Notice;
import com.chaekcheck.sitecontent.internal.web.SitecontentResponses.NoticeLine;
import com.chaekcheck.sitecontent.internal.web.SitecontentResponses.NoticeList;
import com.chaekcheck.sitecontent.internal.web.SitecontentResponses.NoticeVersion;

/**
 * {@code GET /api/v1/notices} and {@code GET /api/v1/notices/{noticeId}} (plan cycle 16 design-delta "API", C4). No
 * login.
 */
@RestController
class NoticeController {

	static final int PAGE_SIZE = 10;

	private final NoticeStore store;

	private final Today today;

	NoticeController(NoticeStore store, Today today) {
		this.store = store;
		this.today = today;
	}

	/**
	 * Ten per page, pinned notices on page 1 only and outside the ten and the total. A page past the last one is
	 * moved to the last one and the response says which page it is; with no notice that is page 1.
	 */
	@GetMapping("/api/v1/notices")
	NoticeList notices(@RequestParam(required = false) String page) {
		int requested = SitecontentParams.positive("page", page, 1);
		LocalDate day = today.get();
		int total = store.countUnpinned(day);
		int lastPage = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
		int shown = Math.min(requested, lastPage);
		List<NoticeLine> pinned = shown == 1 ? lines(store.pinned(day)) : List.of();
		List<NoticeLine> items = lines(store.unpinned(day, PAGE_SIZE, (shown - 1) * PAGE_SIZE));
		return new NoticeList(pinned, items, shown, PAGE_SIZE, total, shown < lastPage);
	}

	/**
	 * The notice first (missing, hidden or not posted yet: {@code notice_not_found}), then the version (missing or
	 * future: {@code version_not_found}). {@code title} is the shown version's; {@code editedOn} is the current
	 * version's day when that is version 2 or later, also when an older version is shown.
	 */
	@GetMapping("/api/v1/notices/{noticeId}")
	Notice notice(@PathVariable String noticeId, @RequestParam(required = false) String version) {
		long id = SitecontentParams.id("noticeId", noticeId);
		Integer requested = SitecontentParams.positive("version", version, null);
		LocalDate day = today.get();
		if (!store.isVisible(id, day)) {
			throw NotFoundException.notice();
		}
		List<VersionDate> versions = store.versions(id, day);
		VersionDate current = versions.getFirst();
		LocalDate postedOn = versions.getLast().effectiveOn();
		int shown = requested == null ? current.number() : requested;
		NoticeStore.Version row = store.version(id, shown, day).orElseThrow(NotFoundException::version);
		return new Notice(id, row.title(), postedOn, current.number() >= 2 ? current.effectiveOn() : null,
				new NoticeVersion(row.number(), row.effectiveOn(), row.number() == current.number(), row.body()),
				SitecontentResponses.versionInfos(versions));
	}

	private static List<NoticeLine> lines(List<NoticeStore.Row> rows) {
		return rows.stream().map(row -> new NoticeLine(row.id(), row.title(), row.postedOn())).toList();
	}

}
