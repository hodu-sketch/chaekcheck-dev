package com.chaekcheck.availability.internal.egress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-lane counts for the one-minute operations summary line (cycle 22; same shape as
 * {@code LookupOpsCounters}): requests sent on each lane, pauses started, retests sent and lanes resumed since the
 * last drain. Per process on purpose, like the other summary counters.
 */
public final class LaneCounters {

	private final Map<String, AtomicLong> requests = new LinkedHashMap<>();

	private final AtomicLong pauses = new AtomicLong();

	private final AtomicLong retests = new AtomicLong();

	private final AtomicLong resumes = new AtomicLong();

	/** Counters for every lane in order, so the summary line always names them all. */
	public LaneCounters(List<EgressLane> lanes) {
		lanes.forEach(lane -> requests.put(lane.id(), new AtomicLong()));
	}

	public void request(String laneId) {
		AtomicLong count;
		synchronized (requests) {
			count = requests.computeIfAbsent(laneId, id -> new AtomicLong());
		}
		count.incrementAndGet();
	}

	public void pauseStarted() {
		pauses.incrementAndGet();
	}

	public void retestSent() {
		retests.incrementAndGet();
	}

	public void resumed() {
		resumes.incrementAndGet();
	}

	/** The counts since the last drain; the counters start again at zero. */
	public Snapshot drain() {
		StringBuilder perLane = new StringBuilder();
		synchronized (requests) {
			requests.forEach((id, count) -> {
				if (!perLane.isEmpty()) {
					perLane.append(';');
				}
				perLane.append(id).append(':').append(count.getAndSet(0));
			});
		}
		return new Snapshot(perLane.toString(), pauses.getAndSet(0), retests.getAndSet(0), resumes.getAndSet(0));
	}

	/** @param requestsPerLane {@code id:count;id:count…} in lane order */
	public record Snapshot(String requestsPerLane, long pauses, long retests, long resumes) {
	}

}
