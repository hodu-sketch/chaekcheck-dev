package com.chaekcheck.availability.internal.search;

/**
 * One unit a batch may run (ADR-0029): a library's search page ({@link Candidate}) or one holding's detail page
 * ({@link HoldingTask}). The batch runner applies the same parallelism, busy-host skipping, row limit, batch deadline
 * and launch cutoff to both.
 */
public sealed interface LookupTask permits Candidate, HoldingTask {

	/**
	 * The host this task requests, as the host throttle names it; null when the task sends no request (a
	 * subscription holding, or a detail URL that may not be requested). Such tasks take no slot.
	 */
	String hostKey();

}
