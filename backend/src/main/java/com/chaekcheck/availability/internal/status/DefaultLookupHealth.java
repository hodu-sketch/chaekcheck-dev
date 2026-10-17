package com.chaekcheck.availability.internal.status;

import java.time.Clock;

import com.chaekcheck.availability.LookupHealth;
import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.state.DbErrorRecord;

/**
 * {@link LookupHealth}: writes the last DB error time into the {@link DbErrorRecord} store and counts it for the
 * operations summary. Only the root {@code ApiExceptionHandler} calls it, so DB errors inside SSE streams or
 * scheduled jobs are not counted (accepted, [안]).
 */
public final class DefaultLookupHealth implements LookupHealth {

	private final DbErrorRecord dbErrors;

	private final LookupOpsCounters counters;

	private final Clock clock;

	public DefaultLookupHealth(DbErrorRecord dbErrors, LookupOpsCounters counters, Clock clock) {
		this.dbErrors = dbErrors;
		this.counters = counters;
		this.clock = clock;
	}

	@Override
	public void recordDbError() {
		dbErrors.mark(clock.instant());
		counters.dbError();
	}

}
