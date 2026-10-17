package com.chaekcheck.availability.internal.state;

import java.time.Instant;
import java.util.Optional;

/**
 * One remembered time behind a store interface (cycle 20, ADR-0133): the last 503 refusal ({@link RefusalRecord})
 * and the last DB error ({@link DbErrorRecord}). The memory implementations are correct while the service runs as
 * one instance; {@code chaekcheck.lookup.state-store} picks the implementation (today {@code memory} only).
 */
public interface LastTimeRecord {

	void mark(Instant at);

	/** The last time marked, or empty when none yet (or since the start, for the memory store). */
	Optional<Instant> last();

	/** Whether a mark happened within {@code window} before {@code now}. */
	default boolean within(java.time.Duration window, Instant now) {
		return last().filter(at -> !at.plus(window).isBefore(now)).isPresent();
	}

}
