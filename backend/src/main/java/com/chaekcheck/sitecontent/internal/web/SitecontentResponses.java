package com.chaekcheck.sitecontent.internal.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.chaekcheck.sitecontent.internal.VersionDate;

/** Response bodies (plan cycle 16 design-delta "API": field names [확정]). Dates are written YYYY-MM-DD. */
final class SitecontentResponses {

	private SitecontentResponses() {
	}

	record NoticeList(List<NoticeLine> pinned, List<NoticeLine> items, int page, int pageSize, int totalCount,
			boolean hasMore) {
	}

	/** A list line: no edited day (AC-13). */
	record NoticeLine(long id, String title, LocalDate postedOn) {
	}

	record Notice(long id, String title, LocalDate postedOn, LocalDate editedOn, NoticeVersion version,
			List<VersionInfo> versions) {
	}

	record NoticeVersion(int number, LocalDate effectiveOn, boolean current, String body) {
	}

	record Document(String key, DocumentVersion version, List<VersionInfo> versions) {
	}

	record DocumentVersion(int number, LocalDate effectiveOn, boolean current, String body, String changeSummary,
			List<ConsentItem> consentItems) {
	}

	record ConsentItem(String label, String text) {
	}

	/** {@code supersededOn}: the next version's effective day; null for the current one. */
	record VersionInfo(int number, LocalDate effectiveOn, LocalDate supersededOn) {
	}

	/** {@code versions} highest first (the current one first), each with the day the next one took over. */
	static List<VersionInfo> versionInfos(List<VersionDate> versions) {
		List<VersionInfo> infos = new ArrayList<>(versions.size());
		LocalDate next = null;
		for (VersionDate version : versions) {
			infos.add(new VersionInfo(version.number(), version.effectiveOn(), next));
			next = version.effectiveOn();
		}
		return infos;
	}

}
