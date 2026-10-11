package com.chaekcheck.popular.internal.schedule;

/** What started a fetch: {@code popular.fetch_run.trigger}. */
public enum FetchTrigger {

	/** the decision at start-up ({@code ApplicationReadyEvent}) */
	STARTUP,
	/** the daily decision at 05:00 */
	SCHEDULED

}
