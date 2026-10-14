package com.chaekcheck.collection.internal.importer;

import java.util.Locale;
import java.util.Set;

import com.chaekcheck.collection.internal.SiteHosts;

/**
 * Which collected holding rows become service holdings: the one rule of the import ({@link CollectedCatalog}) and of
 * the library insert's "no e-books" check (cycle 18, ADR-0110), so both count the same rows. Moved out of
 * {@code CollectedCatalog.skipReason}; the order of the checks is unchanged and gives the reason that is counted.
 */
public final class HoldingRowFilter {

	/** Kinds never imported [확정]. The user chose to import KIDS_BOOK, PAPER and UNKNOWN (2026-09-30). */
	public static final Set<String> EXCLUDED_KINDS = Set.of("AUDIOBOOK", "VIDEO_BOOK");

	/**
	 * Site labels of sound and video files the content type does not catch [확정] (ADR-0054, Q2): Bookcube's file
	 * kinds {@code book/wma}, {@code book/vod}, {@code book/wmv} and Kyobo's {@code 동영상}.
	 */
	public static final Set<String> SOUND_VIDEO_KINDS = Set.of("book/wma", "book/vod", "book/wmv", "동영상");

	private HoldingRowFilter() {
	}

	/**
	 * The first filter a row fails, or null when it is imported: {@code removed}, {@code kind_*},
	 * {@code kind_sound_video}, {@code no_detail_url}, {@code detail_url_not_http}, {@code detail_host_mismatch},
	 * {@code no_title}.
	 * @param libraryHost lower-case host of the library's base URL ({@link SiteHosts#host}); detail URLs must be on it
	 */
	public static String skipReason(String removedAt, String contentType, String contentKind, String detailUrl,
			String title, String libraryHost) {
		if (removedAt != null) {
			return "removed";
		}
		if (contentType == null || EXCLUDED_KINDS.contains(contentType)) {
			return "kind_" + String.valueOf(contentType).toLowerCase(Locale.ROOT);
		}
		if (contentKind != null && SOUND_VIDEO_KINDS.contains(contentKind.strip())) {
			return "kind_sound_video";
		}
		if (detailUrl == null || detailUrl.isBlank()) {
			return "no_detail_url";
		}
		String detailHost = SiteHosts.httpHost(detailUrl);
		if (detailHost == null) {
			return "detail_url_not_http";
		}
		if (!detailHost.equals(libraryHost)) {
			return "detail_host_mismatch";
		}
		if (title == null || title.isBlank()) {
			return "no_title";
		}
		return null;
	}

}
