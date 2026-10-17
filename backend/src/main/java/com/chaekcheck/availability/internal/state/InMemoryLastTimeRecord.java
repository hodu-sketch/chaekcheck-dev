package com.chaekcheck.availability.internal.state;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** {@link RefusalRecord} and {@link DbErrorRecord} in this process's memory (state-store {@code memory}). */
public final class InMemoryLastTimeRecord implements RefusalRecord, DbErrorRecord {

	private final AtomicReference<Instant> last = new AtomicReference<>();

	@Override
	public void mark(Instant at) {
		last.accumulateAndGet(at, (old, now) -> old == null || old.isBefore(now) ? now : old);
	}

	@Override
	public Optional<Instant> last() {
		return Optional.ofNullable(last.get());
	}

}
