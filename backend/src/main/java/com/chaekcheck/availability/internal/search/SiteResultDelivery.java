package com.chaekcheck.availability.internal.search;

import java.util.concurrent.CompletableFuture;

import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Resolution;

/**
 * Sends one library's result to the stream (ADR-0019, design-delta "결과 전달 인터페이스"). The batch runner only
 * waits for the returned future ("this library's delivery is done"); when and what is sent is up to the
 * implementation. Chosen by {@link #forResolution}.
 */
public interface SiteResultDelivery {

	/** @return completes when everything for this library has been sent */
	CompletableFuture<Void> deliver(int batchNo, Resolution resolution, Channel channel);

	/** No PENDING rows: one library-result. Otherwise library-result, then a row-verdict per PENDING row. */
	static SiteResultDelivery forResolution(Resolution resolution) {
		return resolution.hasPending() ? ProgressiveDelivery.INSTANCE : OneShotDelivery.INSTANCE;
	}

	/** Where deliveries write; the batch session serializes the writes. */
	interface Channel {

		void libraryResult(LibraryPage page);

		void rowVerdict(StreamEvents.RowVerdict verdict);

	}

}
