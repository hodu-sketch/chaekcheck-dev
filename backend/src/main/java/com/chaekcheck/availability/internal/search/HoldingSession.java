package com.chaekcheck.availability.internal.search;

import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.chaekcheck.availability.internal.Problems;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;

/**
 * One open holding stream (cycle 04). Results arrive on many threads; every method is synchronized so events go out
 * one at a time: holding events -> batch-done -> done. Each requested holding gets exactly one holding-result or
 * holding-failed. One holding is one row for the row limit.
 */
class HoldingSession implements BatchRunner.LaunchSession<HoldingTask> {

	private static final Logger log = LoggerFactory.getLogger(HoldingSession.class);

	private final EventSink sink;

	private final int batchNo;

	private final ResultFilter filter;

	private final int rowLimit;

	private final Set<HoldingTask> inFlight = new LinkedHashSet<>();

	private int requested;

	private int succeeded;

	private int failed;

	private int countedRows;

	private StreamEvents.StopReason stopReason;

	private boolean done;

	private boolean clientGone;

	/** Gives the search slot back; runs once, after the last event. */
	private final Runnable onFinished;

	HoldingSession(EventSink sink, int batchNo, ResultFilter filter, int rowLimit, Runnable onFinished) {
		this.sink = sink;
		this.onFinished = onFinished;
		this.batchNo = batchNo;
		this.filter = filter;
		this.rowLimit = rowLimit;
	}

	synchronized void targets(StreamEvents.HoldingTargets targets) {
		send(StreamEvents.TARGETS, targets);
	}

	/**
	 * A candidate holding that is gone ({@code holding_not_found}) or whose library is not usable
	 * ({@code library_not_found}): fails on its own (ADR-0070).
	 */
	synchronized void notFound(MissingHolding missing) {
		failed++;
		send(StreamEvents.HOLDING_FAILED, new StreamEvents.HoldingFailed(batchNo, missing.holdingId(), 0, 0,
				Problems.asMap(Problems.problem(HttpStatus.NOT_FOUND, missing.code(), missing.detail()))));
	}

	@Override
	public synchronized boolean rowLimitReached() {
		return countedRows > rowLimit;
	}

	@Override
	public synchronized void requested(HoldingTask task) {
		inFlight.add(task);
		requested++;
		send(StreamEvents.HOLDING_REQUESTED, new StreamEvents.HoldingRequested(batchNo, task.holding().holdingId(),
				task.holding().bookId(), task.holding().libraryId()));
	}

	/** @param task a requested task, or a task without a request (subscription) */
	synchronized void succeeded(HoldingTask task, StreamEvents.HoldingResult result) {
		if (task.hostKey() != null && !inFlight.remove(task)) {
			return; // already failed at the batch deadline
		}
		succeeded++;
		Availability availability = result.item().availability();
		if (filter != ResultFilter.AVAILABLE || availability == Availability.AVAILABLE) {
			countedRows++;
		}
		send(StreamEvents.HOLDING_RESULT, result);
		finishIfDone();
	}

	synchronized void failed(HoldingTask task, UpstreamException ex) {
		if (task.hostKey() != null && !inFlight.remove(task)) {
			return;
		}
		failed++;
		log.info("batch={} holding={} library={} failed: {} {}", batchNo, task.holding().holdingId(),
				task.holding().libraryId(), ex.kind(), ex.getMessage());
		send(StreamEvents.HOLDING_FAILED, new StreamEvents.HoldingFailed(batchNo, task.holding().holdingId(),
				task.holding().bookId(), task.holding().libraryId(), Problems.asMap(Problems.upstream(ex))));
		finishIfDone();
	}

	@Override
	public synchronized void deadlinePassed() {
		for (HoldingTask task : Set.copyOf(inFlight)) {
			failed(task, UpstreamException.timeout("batch deadline passed"));
		}
	}

	@Override
	public synchronized void launchFinished(StreamEvents.StopReason reason) {
		stopReason = reason;
		finishIfDone();
	}

	@Override
	public synchronized int requestedCount() {
		return requested;
	}

	private void finishIfDone() {
		if (done || stopReason == null || !inFlight.isEmpty()) {
			return;
		}
		done = true;
		try {
			send(StreamEvents.BATCH_DONE,
					new StreamEvents.BatchDone(batchNo, requested, succeeded, failed, countedRows, 0, stopReason));
			send(StreamEvents.DONE, new StreamEvents.Done(batchNo));
			if (!clientGone) {
				sink.complete();
			}
		}
		finally {
			onFinished.run();
		}
	}

	private void send(String name, Object data) {
		if (!clientGone && !sink.send(name, data)) {
			clientGone = true;
			log.info("holding stream client disconnected; remaining lookups still finish and fill the cache");
		}
	}

}
