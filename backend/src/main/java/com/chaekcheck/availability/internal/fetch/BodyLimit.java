package com.chaekcheck.availability.internal.fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Reads a response body into memory but stops at {@code maxBytes}: a larger body (declared by Content-Length or
 * counted while reading) fails with {@link TooLargeException} and the exchange is cancelled, so no more bytes are
 * read. Used by {@link DirectSiteFetcher}; the fetch service has the same class.
 */
final class BodyLimit {

	private BodyLimit() {
	}

	static HttpResponse.BodyHandler<byte[]> handler(long maxBytes) {
		return info -> new Subscriber(maxBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1));
	}

	/** The body passed the limit. An IOException, so the HTTP client reports it like a broken read. */
	static final class TooLargeException extends IOException {

		TooLargeException(long maxBytes) {
			super("response body over " + maxBytes + " bytes");
		}

	}

	private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {

		private final long maxBytes;

		private final long declaredLength;

		private final CompletableFuture<byte[]> result = new CompletableFuture<>();

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		private Flow.Subscription subscription;

		private long received;

		Subscriber(long maxBytes, long declaredLength) {
			this.maxBytes = maxBytes;
			this.declaredLength = declaredLength;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			if (declaredLength > maxBytes) {
				tooLarge();
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
				received += item.remaining();
				if (received > maxBytes) {
					tooLarge();
					return;
				}
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				out.writeBytes(chunk);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			result.complete(out.toByteArray());
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return result;
		}

		private void tooLarge() {
			subscription.cancel();
			result.completeExceptionally(new TooLargeException(maxBytes));
		}

	}

}
