package com.chaekcheck.availability.internal.search;

import java.util.concurrent.CompletableFuture;

import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.PendingRow;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Resolution;

/**
 * Some rows PENDING: library-result with the PENDING rows, then one row-verdict per row as it is decided. The
 * resolver guarantees every PENDING future completes by its own deadline, so this never waits forever.
 */
final class ProgressiveDelivery implements SiteResultDelivery {

	static final ProgressiveDelivery INSTANCE = new ProgressiveDelivery();

	private ProgressiveDelivery() {
	}

	@Override
	public CompletableFuture<Void> deliver(int batchNo, Resolution resolution, Channel channel) {
		channel.libraryResult(resolution.page());
		CompletableFuture<?>[] verdicts = new CompletableFuture<?>[resolution.pending().size()];
		for (int i = 0; i < verdicts.length; i++) {
			PendingRow row = resolution.pending().get(i);
			verdicts[i] = row.decision()
				.thenAccept(decision -> channel.rowVerdict(new StreamEvents.RowVerdict(batchNo,
						resolution.page().libraryId(), resolution.page().page(), row.rank(), decision.availability(),
						decision.basis())));
		}
		return CompletableFuture.allOf(verdicts);
	}

}
