package com.chaekcheck.fetch;

import java.time.Instant;

/**
 * Answer of {@code POST /v1/fetches} (design-delta API). Always sent with HTTP 200 while this service is healthy;
 * library-side failures are in {@code outcome}.
 * @param status the library's HTTP status; null unless RESPONDED
 * @param body the library's raw body bytes, base64; null unless RESPONDED
 */
public record FetchResult(Outcome outcome, Integer status, String contentType, String location, String body,
		Instant startedAt, Instant endedAt) {

	public enum Outcome {
		/** The library answered (any status, redirects included). */
		RESPONDED,
		/** The library did not answer within the read timeout. */
		TIMEOUT,
		/** Could not connect to the library (refused or connect timeout). */
		CONNECT_FAILED,
		/** Any other failure talking to the library. */
		ERROR
	}

	static FetchResult failed(Outcome outcome, Instant startedAt, Instant endedAt) {
		return new FetchResult(outcome, null, null, null, null, startedAt, endedAt);
	}

}
