package com.chaekcheck.availability.internal.fetch;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link HostQueueBoard} in this process's memory (state-store {@code memory}, ADR-0133): one row per
 * {@code (lane, host)} while something is waiting or a timeout is fresh, holding the start time of every open wait
 * per library and the libraries seen on the host. A row is dropped when it has no wait and no timeout left.
 * {@code ConcurrentHashMap} for the rows, skip lists inside so one host's libraries come in id order [안].
 */
public final class InMemoryHostQueueBoard implements HostQueueBoard {

	private final Map<String, HostRow> rows = new ConcurrentHashMap<>();

	@Override
	public Wait begin(String laneId, String hostKey, long libraryId, Instant now) {
		HostRow row = rows.computeIfAbsent(key(laneId, hostKey), k -> new HostRow());
		row.seen.add(libraryId);
		OpenWait wait = new OpenWait(now);
		row.waits.computeIfAbsent(libraryId, id -> ConcurrentHashMap.newKeySet()).add(wait);
		return new Wait() {

			@Override
			public void end() {
				if (wait.ended.compareAndSet(false, true)) {
					row.remove(libraryId, wait);
				}
			}

			@Override
			public boolean ended() {
				return wait.ended.get();
			}

		};
	}

	@Override
	public void timedOut(String laneId, String hostKey, Instant now) {
		rows.computeIfAbsent(key(laneId, hostKey), k -> new HostRow()).lastTimeout = now;
	}

	@Override
	public Set<Long> queuedLibraryIds(Instant now, Duration waitWindow, Duration timeoutWindow, int limit) {
		Set<Long> queued = new LinkedHashSet<>();
		Instant waitsSince = now.minus(waitWindow);
		Instant timeoutsSince = now.minus(timeoutWindow);
		for (Map.Entry<String, HostRow> entry : rows.entrySet()) {
			HostRow row = entry.getValue();
			Instant timeout = row.lastTimeout;
			boolean hostTimedOut = timeout != null && !timeout.isBefore(timeoutsSince);
			boolean anyWait = false;
			for (Map.Entry<Long, Set<OpenWait>> byLibrary : row.waits.entrySet()) {
				boolean waiting = byLibrary.getValue().stream().anyMatch(w -> !w.startedAt.isBefore(waitsSince));
				anyWait |= !byLibrary.getValue().isEmpty();
				if (waiting && queued.size() < limit) {
					queued.add(byLibrary.getKey());
				}
			}
			if (hostTimedOut) {
				for (Long libraryId : row.seen) {
					if (queued.size() >= limit) {
						break;
					}
					queued.add(libraryId);
				}
			}
			else if (!anyWait) {
				// nothing waiting and no fresh timeout: the row is not needed any more
				rows.remove(entry.getKey(), row);
			}
		}
		return Collections.unmodifiableSet(queued);
	}

	@Override
	public int waitingCount(Instant now, Duration waitWindow) {
		Instant since = now.minus(waitWindow);
		int count = 0;
		for (HostRow row : rows.values()) {
			for (Set<OpenWait> waits : row.waits.values()) {
				count += (int) waits.stream().filter(w -> !w.startedAt.isBefore(since)).count();
			}
		}
		return count;
	}

	private static String key(String laneId, String hostKey) {
		return laneId + "|" + hostKey;
	}

	private static final class OpenWait {

		final Instant startedAt;

		final AtomicBoolean ended = new AtomicBoolean();

		OpenWait(Instant startedAt) {
			this.startedAt = startedAt;
		}

	}

	private static final class HostRow {

		/** library -> open waits, ascending library id */
		final Map<Long, Set<OpenWait>> waits = new ConcurrentSkipListMap<>();

		/** libraries seen waiting on this host since the start (learned, not configured: a restart forgets them) */
		final Set<Long> seen = new ConcurrentSkipListSet<>();

		volatile Instant lastTimeout;

		void remove(long libraryId, OpenWait wait) {
			waits.computeIfPresent(libraryId, (id, set) -> {
				set.remove(wait);
				return set.isEmpty() ? null : set;
			});
		}

	}

}
