package com.chaekcheck.popular.internal.fetch;

import java.io.IOException;

/**
 * Sends requests to the site. One {@link Connection} per fetch: it keeps the session cookies of that fetch only.
 * Tests replace this with a fake (plan cycle 10 C3).
 */
public interface KpipaTransport {

	Connection open();

	/** Requests of one fetch, one at a time. */
	interface Connection extends AutoCloseable {

		/**
		 * @throws IOException on connection failure, time-out ({@link java.net.http.HttpTimeoutException}) or a body
		 * over the size limit ({@link BodyTooLargeException})
		 */
		KpipaResponse send(KpipaRequest request) throws IOException, InterruptedException;

		@Override
		void close();

	}

	/** The answer passed the body size limit. */
	final class BodyTooLargeException extends IOException {

		public BodyTooLargeException(long maxBytes) {
			super("response body over " + maxBytes + " bytes");
		}

	}

}
