package com.chaekcheck.picks.internal.schedule;

/** What started a fetch: {@code picks.fetch_run.trigger} [확정] (ADR-0092). */
public enum FetchTrigger {

	/** no list stored, at start-up */
	STARTUP,
	/** Monday 05:00 */
	SCHEDULED,
	/** 05:00 after a failed fetch with no success since: every day until one succeeds */
	RETRY,
	/** the last success is more than 7 days old (start-up or 05:00), or no list stored at 05:00 [안] */
	CATCH_UP

}
