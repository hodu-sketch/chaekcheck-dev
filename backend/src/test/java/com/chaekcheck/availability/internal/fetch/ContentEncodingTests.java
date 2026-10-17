package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

/** Inflating under the body limit (cycle 20, ADR-0132): a bomb is cut right past the limit, not held. */
class ContentEncodingTests {

	@Test
	void identityAndMissingEncodingPassTheBytesThrough() throws IOException {
		byte[] body = { 1, 2, 3 };

		assertThat(ContentEncoding.decoded(null, body, 10)).isSameAs(body);
		assertThat(ContentEncoding.decoded("identity", body, 10)).isSameAs(body);
		assertThat(ContentEncoding.decoded("", body, 10)).isSameAs(body);
	}

	@Test
	void gzipAndXGzipAreInflated() throws IOException {
		byte[] plain = "<html>채식주의자</html>".getBytes();

		assertThat(ContentEncoding.decoded("gzip", gzip(plain), 1024)).isEqualTo(plain);
		assertThat(ContentEncoding.decoded(" GZIP ", gzip(plain), 1024)).isEqualTo(plain);
		assertThat(ContentEncoding.decoded("x-gzip", gzip(plain), 1024)).isEqualTo(plain);
	}

	@Test
	void emptyBodyLabelledGzipIsEmpty() throws IOException {
		assertThat(ContentEncoding.decoded("gzip", new byte[0], 1024)).isEmpty();
	}

	@Test
	void exactlyTheLimitIsReturnedAndOneMoreByteIsTooLarge() throws IOException {
		assertThat(ContentEncoding.decoded("gzip", gzip(new byte[1024]), 1024)).hasSize(1024);

		assertThatThrownBy(() -> ContentEncoding.decoded("gzip", gzip(new byte[1025]), 1024))
			.isInstanceOf(BodyLimit.TooLargeException.class);
	}

	@Test
	void aBombIsCutAtTheLimitWhateverItWouldInflateTo() {
		// 1MB of zeros is about 1KB of gzip; the limit is 64KB, so at most 64KB + one chunk is ever in memory
		byte[] bomb = gzip(new byte[1024 * 1024]);
		assertThat(bomb.length).isLessThan(2048);

		assertThatThrownBy(() -> ContentEncoding.decoded("gzip", bomb, 64 * 1024))
			.isInstanceOfSatisfying(BodyLimit.TooLargeException.class,
					ex -> assertThat(ex.received()).isEqualTo(bomb.length));
	}

	@Test
	void otherEncodingsAreRefused() {
		assertThatThrownBy(() -> ContentEncoding.decoded("br", new byte[] { 1 }, 1024))
			.isInstanceOf(ContentEncoding.UnsupportedEncodingException.class);
		assertThatThrownBy(() -> ContentEncoding.decoded("deflate", new byte[] { 1 }, 1024))
			.isInstanceOf(ContentEncoding.UnsupportedEncodingException.class);
	}

	@Test
	void brokenGzipIsAnIoException() {
		assertThatThrownBy(() -> ContentEncoding.decoded("gzip", new byte[] { 1, 2, 3, 4 }, 1024))
			.isInstanceOf(IOException.class)
			.isNotInstanceOf(BodyLimit.TooLargeException.class);
	}

	static byte[] gzip(byte[] plain) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
				gz.write(plain);
			}
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
