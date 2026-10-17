package com.chaekcheck.availability.internal.fetch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Library requests ask for {@code Accept-Encoding: gzip} (cycle 20, ADR-0132, overall review #6) and the fetchers
 * inflate the answer themselves: {@link java.net.http.HttpClient} does not, and the Apache client's own decoding is
 * off so both paths count the same way. The compressed bytes are already capped by {@link BodyLimit}; inflating
 * counts again and stops as soon as the output passes the same limit (a compression bomb never fills more than
 * {@code maxBytes} + one chunk of memory). The fetch service has the same class.
 * <p>
 * Only gzip is asked for, so only gzip (and its old name {@code x-gzip}) is inflated; another {@code Content-Encoding}
 * fails that library alone [안]. The inflated bytes are the site's bytes, untouched: adapters still decode EUC-KR
 * themselves.
 */
final class ContentEncoding {

	static final String GZIP = "gzip";

	private static final int CHUNK = 16 * 1024;

	private ContentEncoding() {
	}

	/**
	 * The body as the site produced it before transfer compression.
	 * @param contentEncoding the {@code Content-Encoding} header, or null
	 * @throws BodyLimit.TooLargeException when the inflated body passes {@code maxBytes}
	 * @throws UnsupportedEncodingException for an encoding other than gzip or identity
	 * @throws IOException when the gzip data is broken
	 */
	static byte[] decoded(String contentEncoding, byte[] body, long maxBytes) throws IOException {
		String encoding = contentEncoding == null ? "" : contentEncoding.strip().toLowerCase(Locale.ROOT);
		if (encoding.isEmpty() || encoding.equals("identity")) {
			return body;
		}
		if (!encoding.equals(GZIP) && !encoding.equals("x-gzip")) {
			throw new UnsupportedEncodingException(contentEncoding);
		}
		if (body.length == 0) {
			// some servers label an empty answer (a redirect, an error page) gzip without sending a gzip stream
			return body;
		}
		return inflate(body, maxBytes);
	}

	/** Inflates gzip bytes, reading at most {@code maxBytes} + 1 so a bomb is found without holding it. */
	static byte[] inflate(byte[] compressed, long maxBytes) throws IOException {
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed), CHUNK)) {
			byte[] out = new byte[(int) Math.min(maxBytes, Math.max(CHUNK, 4L * compressed.length))];
			int total = 0;
			while (true) {
				if (total == out.length) {
					if (total >= maxBytes) {
						// one more byte would be over the limit; read it to tell "exactly at" from "over"
						if (in.read() == -1) {
							return out;
						}
						throw new BodyLimit.TooLargeException(maxBytes, compressed.length);
					}
					out = Arrays.copyOf(out, (int) Math.min(maxBytes, 2L * out.length));
				}
				int n = in.read(out, total, out.length - total);
				if (n == -1) {
					return total == out.length ? out : Arrays.copyOf(out, total);
				}
				total += n;
			}
		}
	}

	/** A {@code Content-Encoding} this service did not ask for and cannot read. */
	static final class UnsupportedEncodingException extends IOException {

		UnsupportedEncodingException(String encoding) {
			super("unsupported Content-Encoding: " + encoding);
		}

	}

}
