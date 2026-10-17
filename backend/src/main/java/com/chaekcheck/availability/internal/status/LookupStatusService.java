package com.chaekcheck.availability.internal.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.state.DbErrorRecord;
import com.chaekcheck.availability.internal.state.RefusalRecord;
import com.chaekcheck.availability.internal.status.LookupStatus.Level;
import com.chaekcheck.availability.internal.status.LookupStatus.Reason;

/**
 * Judges the status signal (cycle 20, ADR-0118, spec R1 and R4) from memory only: no library and no database is
 * asked. One computed answer is handed out for {@code memo} (1s); callers arriving while it is stale compute once
 * (the memo is behind a lock) [확정].
 * <p>
 * Order [확정] (R1, design review #4):
 * <ol>
 * <li>a DB error within 30s: GRAY, {@code DB_ERROR} — before every other level, RED and {@code NO_LANE} included</li>
 * <li>every slot taken, or a 503 within 10s, or (proxies only and every vendor lane paused): RED, reason
 * {@code NO_LANE} only in the last case</li>
 * <li>half the slots taken or more, or the vendor lanes paused: YELLOW ({@code VENDOR_PAUSED} when paused)</li>
 * <li>else GREEN, {@code NONE}</li>
 * </ol>
 * The settings live under {@code chaekcheck.availability.lookup-status} (docs/maintenance/settings-index.md).
 */
public class LookupStatusService {

	private final SearchSlots slots;

	private final RefusalRecord refusals;

	private final DbErrorRecord dbErrors;

	private final HostQueueBoard queueBoard;

	private final EgressLaneStatus lanes;

	private final AvailabilityProperties.LookupStatus settings;

	private final Clock clock;

	private final AtomicReference<Memo> memo = new AtomicReference<>();

	private final Object computeLock = new Object();

	public LookupStatusService(SearchSlots slots, RefusalRecord refusals, DbErrorRecord dbErrors,
			HostQueueBoard queueBoard, EgressLaneStatus lanes, AvailabilityProperties.LookupStatus settings,
			Clock clock) {
		this.slots = slots;
		this.refusals = refusals;
		this.dbErrors = dbErrors;
		this.queueBoard = queueBoard;
		this.lanes = lanes;
		this.settings = settings;
		this.clock = clock;
	}

	/** The current status, from the memo when it is younger than {@code memo}. */
	public LookupStatus current() {
		Instant now = clock.instant();
		Memo cached = memo.get();
		if (isFresh(cached, now)) {
			return cached.status();
		}
		synchronized (computeLock) {
			cached = memo.get();
			if (isFresh(cached, now)) {
				return cached.status();
			}
			LookupStatus status = compute(now);
			memo.set(new Memo(now, status));
			return status;
		}
	}

	/** The judgement at {@code now}, without the memo (tests). */
	LookupStatus compute(Instant now) {
		int max = slots.max();
		int inUse = Math.min(max, slots.inUse());
		List<Long> queued = List.copyOf(queueBoard.queuedLibraryIds(now, settings.queueWaitWindow(),
				settings.queueTimeoutWindow(), settings.maxQueuedLibraries()));
		boolean vendorPaused = lanes.vendorLanesPaused();
		boolean noLane = vendorPaused && lanes.egressMode() == EgressLaneStatus.Mode.PROXY_ONLY;
		Level level;
		Reason reason;
		if (dbErrors.within(settings.grayAfterDbError(), now)) {
			level = Level.GRAY;
			reason = Reason.DB_ERROR;
		}
		else if (inUse >= max || refusals.within(settings.redAfterRefusal(), now) || noLane) {
			level = Level.RED;
			reason = noLane ? Reason.NO_LANE : Reason.NONE;
		}
		else if (inUse * 2 >= max || vendorPaused) {
			level = Level.YELLOW;
			reason = vendorPaused ? Reason.VENDOR_PAUSED : Reason.NONE;
		}
		else {
			level = Level.GREEN;
			reason = Reason.NONE;
		}
		return new LookupStatus(max, inUse, level, reason, queued, now);
	}

	private boolean isFresh(Memo cached, Instant now) {
		return cached != null && cached.computedAt().plus(settings.memo()).isAfter(now);
	}

	private record Memo(Instant computedAt, LookupStatus status) {
	}

}
