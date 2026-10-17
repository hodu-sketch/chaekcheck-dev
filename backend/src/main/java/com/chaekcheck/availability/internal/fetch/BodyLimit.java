package com.chaekcheck.availability.internal.fetch;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Reads a response body into memory but stops at {@code maxBytes}: a larger body (declared by Content-Length or
 * counted while reading) fails with {@link TooLargeException} and the exchange is cancelled, so no more bytes are
 * read. Used by {@link DirectSiteFetcher}; the fetch service has the same class.
 * <p>
 * Cycle 08 (ADR-0066): the bytes go straight into one array. With a Content-Length within the limit the array has
 * that size from the start and is returned as it is; without one (or when the server sends more than it declared)
 * the array grows by doubling, never past the limit. The body is copied once at most at the end, when fewer bytes
 * came than the array holds.
 */
final class BodyLimit {

	private BodyLimit() {
	}

	static HttpResponse.BodyHandler<byte[]> handler(long maxBytes) {
		return info -> new Subscriber(maxBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1));
	}

	/**
	 * The body passed the limit. An IOException, so the HTTP client reports it like a broken read.
	 * @param received bytes read from the network before the read stopped (the declared length when refused on
	 * Content-Length alone), for the {@code body_too_large} log line (cycle 20)
	 */
	static final class TooLargeException extends IOException {

		private final long received;

		TooLargeException(long maxBytes, long received) {
			super("response body over " + maxBytes + " bytes");
			this.received = received;
		}

		long received() {
			return received;
		}

	}

	private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {

		private final long maxBytes;

		private final long declaredLength;

		private static final int FIRST_SIZE = 16 * 1024;

		private final CompletableFuture<byte[]> result = new CompletableFuture<>();

		private Flow.Subscription subscription;

		private byte[] body;

		private int received;

		Subscriber(long maxBytes, long declaredLength) {
			this.maxBytes = maxBytes;
			this.declaredLength = declaredLength;
			this.body = new byte[(int) (declaredLength >= 0 && declaredLength <= maxBytes ? declaredLength
					: Math.min(FIRST_SIZE, maxBytes))];
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			if (declaredLength > maxBytes) {
				tooLarge(declaredLength);
				return;
			}
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (result.isDone()) {
				return;
			}
			for (ByteBuffer item : items) {
				int length = item.remaining();
				if ((long) received + length > maxBytes) {
					tooLarge((long) received + length);
					return;
				}
				if (received + length > body.length) {
					// no or a wrong Content-Length: grow by doubling, never past the limit
					body = Arrays.copyOf(body, (int) Math.min(maxBytes, Math.max(received + length, 2L * body.length)));
				}
				item.get(body, received, length);
				received += length;
			}
		}

		@Override
		public void onError(Throwable throwable) {
			result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			result.complete(received == body.length ? body : Arrays.copyOf(body, received));
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return result;
		}

		private void tooLarge(long receivedBytes) {
			subscription.cancel();
			result.completeExceptionally(new TooLargeException(maxBytes, receivedBytes));
		}

	}

}
