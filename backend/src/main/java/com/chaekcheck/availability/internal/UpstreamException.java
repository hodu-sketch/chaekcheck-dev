package com.chaekcheck.availability.internal;

/**
 * A library site call failed. ERROR maps to 502 {@code upstream_error}, TIMEOUT to 504 {@code upstream_timeout}.
 * The message is for logs only; it never goes to the client (no upstream bodies in responses).
 */
public class UpstreamException extends RuntimeException {

	public enum Kind {
		ERROR, TIMEOUT
	}

	private final Kind kind;

	public UpstreamException(Kind kind, String message) {
		this(kind, message, null);
	}

	public UpstreamException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}

	public static UpstreamException error(String message) {
		return new UpstreamException(Kind.ERROR, message);
	}

	public static UpstreamException timeout(String message) {
		return new UpstreamException(Kind.TIMEOUT, message);
	}

}
