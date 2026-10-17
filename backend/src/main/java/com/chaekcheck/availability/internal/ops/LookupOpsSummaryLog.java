package com.chaekcheck.availability.internal.ops;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.egress.BlockedLanes;
import com.chaekcheck.availability.internal.egress.LaneCounters;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.status.EgressLaneStatus;

/**
 * One INFO line a minute with the lookup counters (cycle 20, ADR-0132, overall review #5, AC-40), {@code key=value}
 * so a CloudWatch metric filter reads it (cycle 17 adds the alarms: no line for 5 minutes, heap 85%, a 503 surge):
 * <pre>
 * lookup ops: slotsMax=40 slotsInUse=3 slotsMaxInUse=7 refused=0 libraryRequests=120 bodyTooLarge=0 dbErrors=0
 *   parseMax=8 parseInUse=0 parseMaxInUse=5 queuedLibraries=2 pausedLanes=0 heapUsedMb=210 heapMaxMb=512
 *   egressMode=ALL laneRequests=direct:40;proxy-01:39;proxy-02:41 lanePauses=0 laneRetests=0 laneResumes=0
 *   pausedLaneList=-
 * </pre>
 * The counts ({@code refused}, {@code libraryRequests}, {@code bodyTooLarge}, {@code dbErrors}, the two maxima, the
 * {@code lane*} counts) are for the minute just ended and start again at zero; the rest is the state at the moment
 * of writing. Webshare cycle (in cycle 20) adds the lane fields: {@code pausedLanes} = paused (lane, host) pairs, {@code pausedLaneList}
 * = {@code lane@host} of at most {@value #PAUSED_LIST_MAX} of them (the per-pair 0/1 metric; {@code lane_paused} /
 * {@code lane_resumed} lines carry the changes), {@code laneRequests} per lane in lane order. Stage 2 adds
 * the extension requests and the plan weight, stage 3 the ledger size. Same shape as {@code CacheStatsLog}: its own
 * daemon thread, so it does not depend on Spring scheduling being on.
 */
public final class LookupOpsSummaryLog implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(LookupOpsSummaryLog.class);

	private final LookupOpsCounters counters;

	private final SearchSlots slots;

	private final ParseSlots parseSlots;

	private final HostQueueBoard queueBoard;

	private final EgressLaneStatus lanes;

	private final LaneCounters laneCounters;

	private final BlockedLanes blockedLanes;

	private final AvailabilityProperties.LookupStatus statusSettings;

	static final int PAUSED_LIST_MAX = 20;

	private final Clock clock;

	private final ScheduledExecutorService timer;

	public LookupOpsSummaryLog(LookupOpsCounters counters, SearchSlots slots, ParseSlots parseSlots,
			HostQueueBoard queueBoard, EgressLaneStatus lanes, LaneCounters laneCounters, BlockedLanes blockedLanes,
			AvailabilityProperties.LookupStatus statusSettings, Duration interval, Clock clock) {
		this.counters = counters;
		this.slots = slots;
		this.parseSlots = parseSlots;
		this.queueBoard = queueBoard;
		this.lanes = lanes;
		this.laneCounters = laneCounters;
		this.blockedLanes = blockedLanes;
		this.statusSettings = statusSettings;
		this.clock = clock;
		this.timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
			.name("lookup-ops-summary")
			.daemon(true)
			.factory());
		long millis = interval.toMillis();
		timer.scheduleAtFixedRate(this::logNow, millis, millis, TimeUnit.MILLISECONDS);
	}

	/** Writes the line now and starts the next minute's counts at zero. */
	public void logNow() {
		try {
			LookupOpsCounters.Snapshot counts = counters.drain();
			MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
			int queued = queueBoard
				.queuedLibraryIds(clock.instant(), statusSettings.queueWaitWindow(), statusSettings.queueTimeoutWindow(),
						Integer.MAX_VALUE)
				.size();
			LaneCounters.Snapshot laneCounts = laneCounters.drain();
			StringBuilder pausedList = new StringBuilder();
			for (BlockedLanes.Paused paused : blockedLanes.paused(clock.instant())) {
				if (pausedList.length() > 0) {
					pausedList.append(';');
				}
				if (pausedList.chars().filter(c -> c == ';').count() >= PAUSED_LIST_MAX - 1 && pausedList.length() > 0) {
					pausedList.append("…");
					break;
				}
				pausedList.append(paused.laneId()).append('@').append(paused.host());
			}
			log.info("lookup ops: slotsMax={} slotsInUse={} slotsMaxInUse={} refused={} libraryRequests={} "
					+ "bodyTooLarge={} dbErrors={} parseMax={} parseInUse={} parseMaxInUse={} queuedLibraries={} "
					+ "pausedLanes={} heapUsedMb={} heapMaxMb={} egressMode={} laneRequests={} lanePauses={} "
					+ "laneRetests={} laneResumes={} pausedLaneList={}", slots.max(), slots.inUse(),
					Math.max(counts.maxSlotsInUse(), slots.inUse()), counts.refusals(), counts.libraryRequests(),
					counts.bodyTooLarge(), counts.dbErrors(), parseSlots.max(), parseSlots.inUse(),
					Math.max(parseSlots.drainMaxInUse(), parseSlots.inUse()), queued, lanes.pausedLaneCount(),
					heap.getUsed() >> 20, heap.getMax() < 0 ? -1 : heap.getMax() >> 20, lanes.egressMode(),
					laneCounts.requestsPerLane(), laneCounts.pauses(), laneCounts.retests(), laneCounts.resumes(),
					pausedList.length() == 0 ? "-" : pausedList);
		}
		catch (RuntimeException ex) {
			log.warn("lookup ops summary not written: {}", ex.toString());
		}
	}

	@Override
	public void close() {
		timer.shutdownNow();
	}

}
