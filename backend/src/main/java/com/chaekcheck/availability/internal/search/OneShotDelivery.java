package com.chaekcheck.availability.internal.search;

import java.util.concurrent.CompletableFuture;

import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Resolution;

/** Every row decided: one library-result. */
final class OneShotDelivery implements SiteResultDelivery {

	static final OneShotDelivery INSTANCE = new OneShotDelivery();

	private OneShotDelivery() {
	}

	@Override
	public CompletableFuture<Void> deliver(int batchNo, Resolution resolution, Channel channel) {
		channel.libraryResult(resolution.page());
		return CompletableFuture.completedFuture(null);
	}

}
