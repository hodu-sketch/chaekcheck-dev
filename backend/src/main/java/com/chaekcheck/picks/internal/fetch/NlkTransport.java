package com.chaekcheck.picks.internal.fetch;

import java.io.IOException;
import java.net.URI;

/**
 * Sends GET requests to the National Library of Korea (the list API and the covers). Redirects are never followed.
 * Tests replace this with a fake (plan cycle 14 C3).
 */
public interface NlkTransport {

	/**
	 * @param maxBodyBytes largest body read
	 * @throws IOException on connection failure, time-out ({@link java.net.http.HttpTimeoutException}) or a body over
	 * the size limit ({@link BodyTooLargeException}). The message never holds the request URI (it holds the key).
	 */
	NlkResponse get(URI uri, long maxBodyBytes) throws IOException, InterruptedException;

	/** The answer passed the body size limit. */
	final class BodyTooLargeException extends IOException {

		public BodyTooLargeException(long maxBytes) {
			super("response body over " + maxBytes + " bytes");
		}

	}

}
