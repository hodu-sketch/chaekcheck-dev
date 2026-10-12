package com.chaekcheck.picks.internal.fetch;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Reads a body into memory but stops at {@code maxBytes} (declared by Content-Length or counted while reading) with
 * {@link NlkTransport.BodyTooLargeException}. Same as the availability module's {@code BodyLimit}, copied because
 * that one is internal to its module.
 */
final class BodyLimit {

	private static final int FIRST_SIZE = 16 * 1024;

	private BodyLimit() {
	}

	static HttpResponse.BodyHandler<byte[]> handler(long maxBytes) {
		return info -> new Subscriber(maxBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1));
	}

	private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {

		private final long maxBytes;

		private final long declaredLength;

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
				int length = item.remaining();
				if ((long) received + length > maxBytes) {
					tooLarge();
					return;
				}
				if (received + length > body.length) {
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

		private void tooLarge() {
			subscription.cancel();
			result.completeExceptionally(new NlkTransport.BodyTooLargeException(maxBytes));
		}

	}

}
