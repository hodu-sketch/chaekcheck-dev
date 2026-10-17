package com.chaekcheck.fetch;

import java.time.Instant;

/**
 * Answer of {@code POST /v1/fetches} (design-delta API). Always sent with HTTP 200 while this service is healthy;
 * library-side failures are in {@code outcome}.
 * @param status the library's HTTP status; null unless RESPONDED
 * @param body the library's raw body bytes (inflated when the site sent gzip, cycle 20), base64; null unless
 * RESPONDED
 * @param receivedBytes only for {@link Outcome#BODY_TOO_LARGE}: bytes read from the library before the read stopped
 * (the main service logs them); else null
 * @param lane the egress lane the request went through (webshare cycle (in cycle 20)), echoed back; null when the request named a lane
 * this server does not have
 */
public record FetchResult(Outcome outcome, Integer status, String contentType, String location, String body,
		Instant startedAt, Instant endedAt, Long receivedBytes, String lane) {

	public enum Outcome {
		/** The library answered (any status, redirects included). */
		RESPONDED,
		/** The library did not answer within the read timeout. */
		TIMEOUT,
		/** Could not connect to the library (refused or connect timeout). */
		CONNECT_FAILED,
		/**
		 * The answer passed the body limit, compressed or decompressed (cycle 20, ADR-0132). Its own outcome so the
		 * main service can count it as {@code body_too_large}; an older main service treats it like ERROR.
		 */
		BODY_TOO_LARGE,
		/** Any other failure talking to the library, or a lane this server does not have (webshare cycle (in cycle 20)). */
		ERROR
	}

	public FetchResult(Outcome outcome, Integer status, String contentType, String location, String body,
			Instant startedAt, Instant endedAt, Long receivedBytes) {
		this(outcome, status, contentType, location, body, startedAt, endedAt, receivedBytes, null);
	}

	public FetchResult(Outcome outcome, Integer status, String contentType, String location, String body,
			Instant startedAt, Instant endedAt) {
		this(outcome, status, contentType, location, body, startedAt, endedAt, null, null);
	}

	static FetchResult failed(Outcome outcome, Instant startedAt, Instant endedAt) {
		return new FetchResult(outcome, null, null, null, null, startedAt, endedAt, null, null);
	}

	static FetchResult bodyTooLarge(long receivedBytes, Instant startedAt, Instant endedAt) {
		return new FetchResult(Outcome.BODY_TOO_LARGE, null, null, null, null, startedAt, endedAt, receivedBytes, null);
	}

	FetchResult withLane(String laneId) {
		return new FetchResult(outcome, status, contentType, location, body, startedAt, endedAt, receivedBytes, laneId);
	}

}
