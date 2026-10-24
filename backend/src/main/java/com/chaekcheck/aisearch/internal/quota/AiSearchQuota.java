package com.chaekcheck.aisearch.internal.quota;

/**
 * How many AI searches one IP may start (cycle 24, ADR-0152): a minute's and a day's count, kept in memory, never
 * stored. Behind an interface so that a server farm could share it later (ADR-0152 "나중에").
 */
public interface AiSearchQuota {

	/**
	 * Counts one search for the IP when it is allowed.
	 * @param ip the client IP ({@code X-Real-IP}); null or empty share one count
	 */
	Decision tryAcquire(String ip);

	/**
	 * @param allowed the search may start and was counted
	 * @param retryAfterSeconds when refused: seconds until the minute's oldest search leaves the window, or until the
	 *     next day starts when the day's count is used up (design-delta "검토 뒤 덧붙임" [안])
	 */
	record Decision(boolean allowed, long retryAfterSeconds) {

		public static final Decision ALLOWED = new Decision(true, 0);

	}

}
