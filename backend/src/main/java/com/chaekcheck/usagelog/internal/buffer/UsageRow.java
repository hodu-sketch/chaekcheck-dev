package com.chaekcheck.usagelog.internal.buffer;

import java.time.Instant;
import java.util.List;

import com.chaekcheck.usagelog.SearchConditions;

/** One row waiting in memory for one of the usage log tables. */
public sealed interface UsageRow {

	Instant receivedAt();

	/** Rows from the web that name a library; checked against the enabled libraries when written. */
	sealed interface NamesLibrary extends UsageRow {

		Long libraryId();

	}

	record Search(Instant receivedAt, String kind, SearchConditions conditions, Integer resultCount,
			String loginStatus, String clientIp) implements UsageRow {
	}

	record BookView(Instant receivedAt, long bookId, List<Long> residenceRegionIds, List<Long> workRegionIds,
			List<Long> libraryIds, String loginStatus, String clientIp) implements UsageRow {
	}

	record More(Instant receivedAt, String kind, int seq, Long libraryId, SearchConditions conditions,
			String loginStatus, String clientIp) implements UsageRow {
	}

	/** A usage report naming a library; {@link #raw} is kept for {@code rejected_event} if the library is unknown. */
	record LibraryClick(Instant receivedAt, Long libraryId, String place, String screen, Long bookId,
			String loginStatus, String clientIp, String raw) implements NamesLibrary {
	}

	/**
	 * A screen action; {@code libraryId} only for my_library_add/remove. {@link #raw} as in {@link LibraryClick}.
	 */
	record ScreenEvent(Instant receivedAt, String kind, String picksCategory, Long bookId,
			List<Long> residenceRegionIds, List<Long> workRegionIds, Long libraryId, String loginStatus,
			String clientIp, String raw) implements NamesLibrary {
	}

	/** @param rawValues JSON text, already cut to 2KB */
	record Rejected(Instant receivedAt, String clientIp, String reason, String rawValues) implements UsageRow {
	}

}
