package com.chaekcheck.availability.internal.egress;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link BlockedLanes} in this process's memory (state-store {@code memory}): one entry per (lane, host) that has
 * failed at least once; dropped again when a request or a retest succeeds. A restart forgets every pause, so after
 * a restart a blocked lane is tried again and blocked again by the next real requests [안: 감수].
 */
public final class InMemoryBlockedLanes implements BlockedLanes {

	private final int failuresToBlock;

	private final Duration firstPause;

	private final Duration maxPause;

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	public InMemoryBlockedLanes(int failuresToBlock, Duration firstPause, Duration maxPause) {
		if (failuresToBlock < 1 || firstPause.isZero() || firstPause.isNegative()
				|| maxPause.compareTo(firstPause) < 0) {
			throw new IllegalArgumentException(
					"egress block settings: failures-to-block >= 1, first-pause > 0, max-pause >= first-pause");
		}
		this.failuresToBlock = failuresToBlock;
		this.firstPause = firstPause;
		this.maxPause = maxPause;
	}

	@Override
	public boolean isPaused(String laneId, String host, Instant now) {
		Entry entry = entries.get(key(laneId, host));
		return entry != null && entry.paused();
	}

	@Override
	public Pause requestEnded(String laneId, String host, URI uri, boolean blockSignal, Instant now) {
		String key = key(laneId, host);
		if (!blockSignal) {
			entries.remove(key);
			return null;
		}
		Entry entry = entries.computeIfAbsent(key, k -> new Entry());
		synchronized (entry) {
			if (entry.paused()) {
				// a request that was already in flight when the pause started: nothing more to learn
				return null;
			}
			entry.failures++;
			if (entry.failures < failuresToBlock) {
				return null;
			}
			entry.pause = firstPause;
			entry.nextRetestAt = now.plus(firstPause);
			entry.retestUri = uri;
			entry.retesting = false;
			return new Pause(laneId, host, entry.nextRetestAt, firstPause, 0, firstPause.equals(maxPause));
		}
	}

	@Override
	public Pause retestEnded(String laneId, String host, boolean blockSignal, Instant now) {
		String key = key(laneId, host);
		Entry entry = entries.get(key);
		if (entry == null) {
			return null;
		}
		if (!blockSignal) {
			entries.remove(key);
			return null;
		}
		synchronized (entry) {
			entry.retests++;
			Duration doubled = entry.pause.multipliedBy(2);
			entry.pause = doubled.compareTo(maxPause) > 0 ? maxPause : doubled;
			entry.nextRetestAt = now.plus(entry.pause);
			entry.retesting = false;
			return new Pause(laneId, host, entry.nextRetestAt, entry.pause, entry.retests, entry.pause.equals(maxPause));
		}
	}

	@Override
	public List<Retest> dueRetests(Instant now) {
		List<Retest> due = new ArrayList<>();
		for (Map.Entry<String, Entry> e : entries.entrySet()) {
			Entry entry = e.getValue();
			synchronized (entry) {
				if (!entry.paused() || entry.retesting || now.isBefore(entry.nextRetestAt)) {
					continue;
				}
				entry.retesting = true;
				due.add(new Retest(laneOf(e.getKey()), hostOf(e.getKey()), entry.retestUri, entry.pause, entry.retests));
			}
		}
		return due;
	}

	@Override
	public List<Paused> paused(Instant now) {
		List<Paused> paused = new ArrayList<>();
		for (Map.Entry<String, Entry> e : entries.entrySet()) {
			Entry entry = e.getValue();
			if (entry.paused()) {
				paused.add(new Paused(laneOf(e.getKey()), hostOf(e.getKey()), entry.nextRetestAt, entry.retests));
			}
		}
		return paused;
	}

	private static String key(String laneId, String host) {
		return laneId + "|" + host;
	}

	private static String laneOf(String key) {
		return key.substring(0, key.indexOf('|'));
	}

	private static String hostOf(String key) {
		return key.substring(key.indexOf('|') + 1);
	}

	private static final class Entry {

		int failures;

		int retests;

		/** null while only counting failures; set from the first pause on, until the lane resumes */
		volatile Instant nextRetestAt;

		Duration pause;

		URI retestUri;

		boolean retesting;

		/** Paused: from the pause start until a retest succeeds (a pause that ran out stays paused while retesting). */
		boolean paused() {
			return nextRetestAt != null;
		}

	}

}
