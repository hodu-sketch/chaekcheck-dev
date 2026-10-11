package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * The body read into one array (cycle 08 handoff step 5-5, AC-19): the same bytes as the body sent, with a right,
 * missing, too small or too large Content-Length, in many small chunks, and the 4MB limit still stops a larger body.
 */
class BodyLimitTests {

	static final long LIMIT = 4L << 20;

	static final byte[] BODY = new byte[300_000];

	static {
		new Random(8).nextBytes(BODY);
	}

	@Test
	void sameBytesWhateverTheContentLengthSays() {
		for (Long declared : new Long[] { (long) BODY.length, null, 1000L, BODY.length + 5000L }) {
			assertThat(read(BODY, declared, 1000)).as("Content-Length %s", declared).isEqualTo(BODY);
			assertThat(read(BODY, declared, 70_000)).as("Content-Length %s", declared).isEqualTo(BODY);
		}
		assertThat(read(new byte[0], 0L, 10)).isEmpty();
		assertThat(read(new byte[0], null, 10)).isEmpty();
	}

	@Test
	void bodyOverTheLimitFailsAndStopsReading() {
		byte[] big = new byte[(int) LIMIT + 1];
		AtomicBoolean cancelled = new AtomicBoolean();
		assertThatThrownBy(() -> read(big, null, 64 * 1024, cancelled)).hasCauseInstanceOf(BodyLimit.TooLargeException.class);
		assertThat(cancelled).isTrue();
		// declared too large: refused before any byte
		AtomicBoolean refused = new AtomicBoolean();
		assertThatThrownBy(() -> read(new byte[10], LIMIT + 1, 10, refused))
			.hasCauseInstanceOf(BodyLimit.TooLargeException.class);
		assertThat(refused).isTrue();
		// exactly the limit is read
		assertThat(read(new byte[(int) LIMIT], null, 1 << 20)).hasSize((int) LIMIT);
	}

	private static byte[] read(byte[] body, Long declared, int chunk) {
		return read(body, declared, chunk, new AtomicBoolean());
	}

	private static byte[] read(byte[] body, Long declared, int chunk, AtomicBoolean cancelled) {
		HttpHeaders headers = HttpHeaders.of(declared == null ? Map.of()
				: Map.of("Content-Length", List.of(String.valueOf(declared))), (a, b) -> true);
		HttpResponse.BodySubscriber<byte[]> subscriber = BodyLimit.handler(LIMIT).apply(new HttpResponse.ResponseInfo() {
			@Override
			public int statusCode() {
				return 200;
			}

			@Override
			public HttpHeaders headers() {
				return headers;
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_1_1;
			}
		});
		subscriber.onSubscribe(new Flow.Subscription() {
			@Override
			public void request(long n) {
			}

			@Override
			public void cancel() {
				cancelled.set(true);
			}
		});
		for (int start = 0; start < body.length && !cancelled.get(); start += chunk) {
			List<ByteBuffer> items = new ArrayList<>();
			int end = Math.min(body.length, start + chunk);
			// two buffers per call, like the client sends
			int middle = start + (end - start) / 2;
			items.add(ByteBuffer.wrap(body, start, middle - start).slice());
			items.add(ByteBuffer.wrap(body, middle, end - middle).slice());
			subscriber.onNext(items);
		}
		if (!cancelled.get()) {
			subscriber.onComplete();
		}
		try {
			return subscriber.getBody().toCompletableFuture().join();
		}
		catch (CompletionException ex) {
			throw ex;
		}
	}

}
