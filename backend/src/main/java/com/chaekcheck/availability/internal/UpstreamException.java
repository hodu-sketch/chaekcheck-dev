package com.chaekcheck.availability.internal;

import java.net.URI;

/**
 * A library site call failed. ERROR maps to 502 {@code upstream_error}, TIMEOUT to 504 {@code upstream_timeout}.
 * The message is for logs only; it never goes to the client (no upstream bodies in responses).
 * <p>
 * Cycle 20 (ADR-0132): an answer over the body limit (compressed or decompressed) is an ERROR that also carries
 * {@link #bodyTooLarge()}, the address and the bytes received, so the caller can log it and count it as
 * {@code body_too_large} in the usage log.
 */
public class UpstreamException extends RuntimeException {

	public enum Kind {
		ERROR, TIMEOUT
	}

	private final Kind kind;

	private final boolean bodyTooLarge;

	private final URI uri;

	private final long receivedBytes;

	public UpstreamException(Kind kind, String message) {
		this(kind, message, null);
	}

	public UpstreamException(Kind kind, String message, Throwable cause) {
		this(kind, message, cause, false, null, -1);
	}

	private UpstreamException(Kind kind, String message, Throwable cause, boolean bodyTooLarge, URI uri,
			long receivedBytes) {
		super(message, cause);
		this.kind = kind;
		this.bodyTooLarge = bodyTooLarge;
		this.uri = uri;
		this.receivedBytes = receivedBytes;
	}

	public Kind kind() {
		return kind;
	}

	/** The library's answer passed the body limit (cycle 20); {@link #kind()} is ERROR. */
	public boolean bodyTooLarge() {
		return bodyTooLarge;
	}

	/** The address that was requested, when known (set for {@link #bodyTooLarge()}); else null. */
	public URI uri() {
		return uri;
	}

	/** Bytes received from the network before the limit stopped the read, or -1 when not known. */
	public long receivedBytes() {
		return receivedBytes;
	}

	public static UpstreamException error(String message) {
		return new UpstreamException(Kind.ERROR, message);
	}

	public static UpstreamException timeout(String message) {
		return new UpstreamException(Kind.TIMEOUT, message);
	}

	/**
	 * The answer passed the body limit, compressed or decompressed (cycle 20, ADR-0132).
	 * @param receivedBytes bytes read from the network before the read stopped, or -1 when not known
	 */
	public static UpstreamException bodyTooLarge(URI uri, long maxBytes, long receivedBytes, Throwable cause) {
		return new UpstreamException(Kind.ERROR, "response from " + uri + " is over " + maxBytes + " bytes"
				+ (receivedBytes < 0 ? "" : " (received " + receivedBytes + ")"), cause, true, uri, receivedBytes);
	}

}
