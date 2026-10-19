package com.chaekcheck.availability.internal.ops;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.egress.LaneCounters;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.status.EgressLaneStatus;

/**
 * Writes the lookup state to {@code availability.lookup_sample} every {@code ops-sample.interval} (10s), user
 * decision 2026-10-19 ("B: DB에 저장", "파티션 잘 하고"; the header's status light is gone from the web). One row
 * holds the numbers of the {@code lookup ops:} summary line for the window just ended; the window counters are the
 * sample's own ({@code drainSample}), so the one-minute line keeps its counts.
 * <p>
 * At start and then once a day it makes the current month's partition and {@value #MONTHS_AHEAD} more, and drops
 * the month partitions older than {@code ops-sample.retention}. A DB error never reaches a lookup: the sample is
 * lost, a WARN line is written at most once a minute, and the next sample is tried as usual. Same shape as
 * {@code LookupOpsSummaryLog}: its own daemon thread, so it does not depend on Spring scheduling being on.
 */
public final class OpsSampler implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(OpsSampler.class);

	static final int MONTHS_AHEAD = 2;

	static final Duration WARN_EVERY = Duration.ofMinutes(1);

	static final Duration MAINTAIN_EVERY = Duration.ofDays(1);

	private final LookupOpsCounters counters;

	private final SearchSlots slots;

	private final ParseSlots parseSlots;

	private final HostQueueBoard queueBoard;

	private final EgressLaneStatus lanes;

	private final LaneCounters laneCounters;

	private final AvailabilityProperties.LookupStatus statusSettings;

	private final OpsSampleStore store;

	private final Duration interval;

	private final Duration retention;

	private final Clock clock;

	private final Supplier<MemoryUsage> heap;

	private final Object lock = new Object();

	private Instant windowStart;

	private Instant lastWarnAt;

	private int failuresSinceWarn;

	private ScheduledExecutorService timer;

	public OpsSampler(LookupOpsCounters counters, SearchSlots slots, ParseSlots parseSlots, HostQueueBoard queueBoard,
			EgressLaneStatus lanes, LaneCounters laneCounters, AvailabilityProperties.LookupStatus statusSettings,
			OpsSampleStore store, Duration interval, Duration retention, Clock clock) {
		this(counters, slots, parseSlots, queueBoard, lanes, laneCounters, statusSettings, store, interval, retention,
				clock, () -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());
	}

	OpsSampler(LookupOpsCounters counters, SearchSlots slots, ParseSlots parseSlots, HostQueueBoard queueBoard,
			EgressLaneStatus lanes, LaneCounters laneCounters, AvailabilityProperties.LookupStatus statusSettings,
			OpsSampleStore store, Duration interval, Duration retention, Clock clock, Supplier<MemoryUsage> heap) {
		if (interval == null || interval.isNegative() || interval.isZero()) {
			throw new IllegalArgumentException("ops-sample.interval must be positive: " + interval);
		}
		if (retention == null || retention.isNegative() || retention.isZero()) {
			throw new IllegalArgumentException("ops-sample.retention must be positive: " + retention);
		}
		this.counters = counters;
		this.slots = slots;
		this.parseSlots = parseSlots;
		this.queueBoard = queueBoard;
		this.lanes = lanes;
		this.laneCounters = laneCounters;
		this.statusSettings = statusSettings;
		this.store = store;
		this.interval = interval;
		this.retention = retention;
		this.clock = clock;
		this.heap = heap;
		this.windowStart = clock.instant();
	}

	/** Starts the timer: partitions now and daily, a sample every interval. */
	public void start() {
		ScheduledExecutorService started;
		synchronized (lock) {
			if (timer != null) {
				return;
			}
			started = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
				.name("lookup-ops-sample")
				.daemon(true)
				.factory());
			timer = started;
			// the first window starts with the timer, not with whatever was counted before
			windowStart = clock.instant();
			counters.drainSample();
			laneCounters.drainSample();
			parseSlots.drainSampleMaxInUse();
		}
		started.scheduleAtFixedRate(this::maintainNow, 0, MAINTAIN_EVERY.toMillis(), TimeUnit.MILLISECONDS);
		long millis = interval.toMillis();
		started.scheduleAtFixedRate(this::sampleNow, millis, millis, TimeUnit.MILLISECONDS);
	}

	/** Takes the window's numbers (the window counters start again at zero) and writes them. */
	public void sampleNow() {
		OpsSample sample;
		try {
			sample = take();
		}
		catch (RuntimeException ex) {
			failed("not taken", ex);
			return;
		}
		try {
			store.insert(sample);
		}
		catch (RuntimeException ex) {
			failed("not written", ex);
		}
	}

	/** Makes the coming month partitions and drops those older than the retention. */
	public void maintainNow() {
		try {
			int created = store.ensurePartitions(MONTHS_AHEAD);
			int dropped = store.dropBefore(clock.instant().minus(retention));
			if (created > 0 || dropped > 0) {
				log.info("lookup sample partitions: created={} dropped={} retention={}", created, dropped, retention);
			}
		}
		catch (RuntimeException ex) {
			failed("partitions not kept", ex);
		}
	}

	/** The numbers of the window that ends now; the window counters start again at zero. */
	OpsSample take() {
		synchronized (lock) {
			Instant now = clock.instant();
			long windowMs = Math.max(0, Duration.between(windowStart, now).toMillis());
			windowStart = now;
			LookupOpsCounters.Snapshot counts = counters.drainSample();
			LaneCounters.SampleSnapshot laneCounts = laneCounters.drainSample();
			int parseMaxInUse = parseSlots.drainSampleMaxInUse();
			int slotsInUse = slots.inUse();
			int parseInUse = parseSlots.inUse();
			int queued = queueBoard
				.queuedLibraryIds(now, statusSettings.queueWaitWindow(), statusSettings.queueTimeoutWindow(),
						Integer.MAX_VALUE)
				.size();
			MemoryUsage memory = heap.get();
			return new OpsSample(now, toInt(windowMs), slots.max(), slotsInUse,
					Math.max(counts.maxSlotsInUse(), slotsInUse), toInt(counts.refusals()),
					toInt(counts.libraryRequests()), toInt(counts.bodyTooLarge()), toInt(counts.dbErrors()),
					parseSlots.max(), parseInUse, Math.max(parseMaxInUse, parseInUse), queued, lanes.pausedLaneCount(),
					toInt(memory.getUsed() >> 20), memory.getMax() < 0 ? null : toInt(memory.getMax() >> 20),
					lanes.egressMode().toString(), toInt(laneCounts.requests()), toInt(laneCounts.pauses()),
					toInt(laneCounts.retests()), toInt(laneCounts.resumes()));
		}
	}

	private void failed(String what, RuntimeException ex) {
		Instant now = clock.instant();
		synchronized (lock) {
			failuresSinceWarn++;
			if (lastWarnAt != null && Duration.between(lastWarnAt, now).compareTo(WARN_EVERY) < 0) {
				return;
			}
			log.warn("lookup sample {} ({} failures since the last warning): {}", what, failuresSinceWarn,
					ex.toString());
			lastWarnAt = now;
			failuresSinceWarn = 0;
		}
	}

	private static int toInt(long value) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
	}

	@Override
	public void close() {
		synchronized (lock) {
			if (timer != null) {
				timer.shutdownNow();
			}
		}
	}

}
