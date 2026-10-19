package com.chaekcheck.availability.internal.egress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-lane counts for the one-minute operations summary line (webshare cycle (in cycle 20); same shape as
 * {@code LookupOpsCounters}): requests sent on each lane, pauses started, retests sent and lanes resumed since the
 * last drain. Per process on purpose, like the other summary counters.
 */
public final class LaneCounters {

	private final Map<String, AtomicLong> requests = new LinkedHashMap<>();

	private final AtomicLong pauses = new AtomicLong();

	private final AtomicLong retests = new AtomicLong();

	private final AtomicLong resumes = new AtomicLong();

	// the 10-second DB sample's own window (user decision 2026-10-19): all lanes together
	private final AtomicLong sampleRequests = new AtomicLong();

	private final AtomicLong samplePauses = new AtomicLong();

	private final AtomicLong sampleRetests = new AtomicLong();

	private final AtomicLong sampleResumes = new AtomicLong();

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
		sampleRequests.incrementAndGet();
	}

	public void pauseStarted() {
		pauses.incrementAndGet();
		samplePauses.incrementAndGet();
	}

	public void retestSent() {
		retests.incrementAndGet();
		sampleRetests.incrementAndGet();
	}

	public void resumed() {
		resumes.incrementAndGet();
		sampleResumes.incrementAndGet();
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

	/** The counts since the last sample, every lane together; the sample's counters start again at zero. */
	public SampleSnapshot drainSample() {
		return new SampleSnapshot(sampleRequests.getAndSet(0), samplePauses.getAndSet(0), sampleRetests.getAndSet(0),
				sampleResumes.getAndSet(0));
	}

	/** @param requests requests sent on all lanes together */
	public record SampleSnapshot(long requests, long pauses, long retests, long resumes) {
	}

	/** @param requestsPerLane {@code id:count;id:count…} in lane order */
	public record Snapshot(String requestsPerLane, long pauses, long retests, long resumes) {
	}

}
