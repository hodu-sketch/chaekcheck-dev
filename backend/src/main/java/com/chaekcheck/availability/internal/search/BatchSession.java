package com.chaekcheck.availability.internal.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.chaekcheck.availability.internal.Problems;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Resolution;

/**
 * One open batch stream. Results arrive on many threads; every method is synchronized so events go out one at a
 * time, in the documented order: library events -> batch-done -> row-verdicts -> done. Row verdicts decided before
 * batch-done are held until it is sent. Each requested library gets exactly one library-result or library-failed.
 */
class BatchSession implements SiteResultDelivery.Channel, BatchRunner.LaunchSession<Candidate> {

	private static final Logger log = LoggerFactory.getLogger(BatchSession.class);

	private final EventSink sink;

	private final int batchNo;

	private final ResultFilter filter;

	private final int rowLimit;

	private final Set<Candidate> inFlight = new LinkedHashSet<>();

	private final List<CompletableFuture<Void>> deliveries = new ArrayList<>();

	private final List<StreamEvents.RowVerdict> heldVerdicts = new ArrayList<>();

	private int requested;

	private int succeeded;

	private int failed;

	private int countedRows;

	private int pendingRows;

	private StreamEvents.StopReason stopReason;

	private boolean batchDoneSent;

	private boolean finished;

	private boolean clientGone;

	/** Gives the search slot back; runs once, after the last event. */
	private final Runnable onFinished;

	/**
	 * First stream only (cycle 15 usage log), else null: called exactly once, with the counted rows when batch-done is
	 * sent, or with null when the stream ends before that (client gone, done without batch-done).
	 */
	private Consumer<Integer> searched;

	BatchSession(EventSink sink, int batchNo, ResultFilter filter, int rowLimit, Runnable onFinished,
			Consumer<Integer> searched) {
		this.sink = sink;
		this.onFinished = onFinished;
		this.searched = searched;
		this.batchNo = batchNo;
		this.filter = filter;
		this.rowLimit = rowLimit;
	}

	synchronized void targets(StreamEvents.Targets targets) {
		send(StreamEvents.TARGETS, targets);
	}

	/** A candidate whose library is missing or disabled: fails on its own (library_not_found). */
	synchronized void notFound(long libraryId, int page) {
		failed++;
		send(StreamEvents.LIBRARY_FAILED, new StreamEvents.LibraryFailed(batchNo, libraryId, page,
				Problems.asMap(Problems.problem(HttpStatus.NOT_FOUND, "library_not_found",
						"The library does not exist or is disabled"))));
	}

	@Override
	public synchronized boolean rowLimitReached() {
		return countedRows > rowLimit;
	}

	@Override
	public synchronized void requested(Candidate candidate) {
		inFlight.add(candidate);
		requested++;
		send(StreamEvents.LIBRARY_REQUESTED,
				new StreamEvents.LibraryRequested(batchNo, candidate.target().id(), candidate.page()));
	}

	synchronized void succeeded(Candidate candidate, Resolution resolution) {
		if (!inFlight.remove(candidate)) {
			return; // already failed at the batch deadline
		}
		succeeded++;
		for (LibraryPage.Item item : resolution.page().items()) {
			if (counts(item.availability())) {
				countedRows++;
			}
		}
		pendingRows += resolution.pending().size();
		deliveries.add(SiteResultDelivery.forResolution(resolution).deliver(batchNo, resolution, this));
		finishBatchIfDone();
	}

	synchronized void failed(Candidate candidate, UpstreamException ex) {
		if (!inFlight.remove(candidate)) {
			return;
		}
		failed++;
		log.info("batch={} library={} page={} failed: {} {}", batchNo, candidate.target().id(), candidate.page(),
				ex.kind(), ex.getMessage());
		send(StreamEvents.LIBRARY_FAILED, new StreamEvents.LibraryFailed(batchNo, candidate.target().id(),
				candidate.page(), Problems.asMap(Problems.upstream(ex))));
		finishBatchIfDone();
	}

	/** Batch deadline: every requested library without an answer fails with upstream_timeout (AC-7). */
	@Override
	public synchronized void deadlinePassed() {
		for (Candidate candidate : List.copyOf(inFlight)) {
			failed(candidate, UpstreamException.timeout("batch deadline passed"));
		}
	}

	/** No more requests will start. */
	@Override
	public synchronized void launchFinished(StreamEvents.StopReason reason) {
		stopReason = reason;
		finishBatchIfDone();
	}

	@Override
	public synchronized void libraryResult(LibraryPage page) {
		send(StreamEvents.LIBRARY_RESULT, page);
	}

	@Override
	public synchronized void rowVerdict(StreamEvents.RowVerdict verdict) {
		if (batchDoneSent) {
			send(StreamEvents.ROW_VERDICT, verdict);
		}
		else {
			heldVerdicts.add(verdict);
		}
	}

	@Override
	public synchronized int requestedCount() {
		return requested;
	}

	synchronized Summary summary() {
		return new Summary(requested, succeeded, failed, countedRows, pendingRows, stopReason);
	}

	private boolean counts(Availability availability) {
		return filter == ResultFilter.AVAILABLE ? availability == Availability.AVAILABLE
				: availability != Availability.PENDING;
	}

	private void finishBatchIfDone() {
		if (batchDoneSent || stopReason == null || !inFlight.isEmpty()) {
			return;
		}
		batchDoneSent = true;
		send(StreamEvents.BATCH_DONE, new StreamEvents.BatchDone(batchNo, requested, succeeded, failed, countedRows,
				pendingRows, stopReason));
		searched(clientGone ? null : countedRows);
		heldVerdicts.forEach(verdict -> send(StreamEvents.ROW_VERDICT, verdict));
		heldVerdicts.clear();
		CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).whenComplete((v, ex) -> finish());
	}

	private synchronized void finish() {
		if (finished) {
			return;
		}
		finished = true;
		searched(null);
		try {
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
			log.info("batch stream client disconnected; remaining lookups still finish and fill the cache");
			searched(null);
		}
	}

	/** Records the search once; a failure there never touches the stream. */
	private void searched(Integer rows) {
		Consumer<Integer> once = searched;
		searched = null;
		if (once == null) {
			return;
		}
		try {
			once.accept(rows);
		}
		catch (RuntimeException ex) {
			log.warn("search not recorded: {}", ex.toString());
		}
	}

	record Summary(int requested, int succeeded, int failed, int countedRows, int pendingRows,
			StreamEvents.StopReason stopReason) {
	}

}
