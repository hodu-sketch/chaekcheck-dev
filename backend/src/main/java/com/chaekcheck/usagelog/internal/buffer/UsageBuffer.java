package com.chaekcheck.usagelog.internal.buffer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.chaekcheck.usagelog.UsageLog.ResponseResult;
import com.chaekcheck.usagelog.UsageLog.ResponseSource;

/**
 * Rows waiting for the next write (ADR-0097). One bounded queue for the event rows [확정 10,000]: when it is full a new
 * row is dropped and counted, and the count goes to {@code dropped_batch} ({@code buffer_full}) with the next write.
 * The library answers are not rows but counts per (day, library, source), outside the queue limit; there are at most
 * a few hundred libraries a day.
 */
public class UsageBuffer {

	private final ArrayBlockingQueue<UsageRow> rows;

	private final AtomicLong droppedWhileFull = new AtomicLong();

	/** Adding takes the read lock (many at once), taking everything out the write lock, so no count is lost. */
	private final ReadWriteLock responsesLock = new ReentrantReadWriteLock();

	private Map<ResponseKey, ResponseCounts> responses = new ConcurrentHashMap<>();

	public UsageBuffer(int capacity) {
		this.rows = new ArrayBlockingQueue<>(capacity);
	}

	/** Queues a row; false (and counted) when the queue is full. Never blocks. */
	public boolean offer(UsageRow row) {
		if (rows.offer(row)) {
			return true;
		}
		droppedWhileFull.incrementAndGet();
		return false;
	}

	public void addResponse(LocalDate day, long libraryId, ResponseSource source, ResponseResult result, long elapsedMs) {
		responsesLock.readLock().lock();
		try {
			responses.computeIfAbsent(new ResponseKey(day, libraryId, source), key -> new ResponseCounts())
				.add(result, elapsedMs);
		}
		finally {
			responsesLock.readLock().unlock();
		}
	}

	/** Takes out everything waiting: the rows, the library answer counts and the number dropped while full. */
	public Batch drain() {
		List<UsageRow> taken = new ArrayList<>();
		rows.drainTo(taken);
		Map<ResponseKey, ResponseCounts> takenResponses;
		responsesLock.writeLock().lock();
		try {
			takenResponses = responses;
			responses = new ConcurrentHashMap<>();
		}
		finally {
			responsesLock.writeLock().unlock();
		}
		Map<ResponseKey, ResponseTotals> totals = new HashMap<>();
		takenResponses.forEach((key, counts) -> totals.put(key, counts.totals()));
		return new Batch(taken, totals, droppedWhileFull.getAndSet(0));
	}

	public int size() {
		return rows.size();
	}

	/** @param droppedWhileFull rows refused since the last drain because the queue was full */
	public record Batch(List<UsageRow> rows, Map<ResponseKey, ResponseTotals> responses, long droppedWhileFull) {

		public boolean isEmpty() {
			return rows.isEmpty() && responses.isEmpty() && droppedWhileFull == 0;
		}

		/** Rows given up if this batch fails: the event rows and one per library answer line. */
		public int rowCount() {
			return rows.size() + responses.size();
		}

	}

	public record ResponseKey(LocalDate day, long libraryId, ResponseSource source) {
	}

	/** @param bodyTooLarge answers over the body limit (cycle 20); each is also one of {@code failed} */
	public record ResponseTotals(int ok, int failed, int timeout, int skipped, long okMsTotal, int okMsMax,
			int cached, int bodyTooLarge) {
	}

	private static final class ResponseCounts {

		private int ok;

		private int failed;

		private int timeout;

		private int skipped;

		/** Answers from the cache (cycle 19, ADR-0116): no request, no time. */
		private int cached;

		/** Over the body limit (cycle 20, ADR-0132): one failure, and this count besides. */
		private int bodyTooLarge;

		private long okMsTotal;

		private int okMsMax;

		synchronized void add(ResponseResult result, long elapsedMs) {
			switch (result) {
				case OK -> {
					ok++;
					int ms = (int) Math.min(Math.max(elapsedMs, 0), Integer.MAX_VALUE);
					okMsTotal += ms;
					okMsMax = Math.max(okMsMax, ms);
				}
				case FAILED -> failed++;
				case TIMEOUT -> timeout++;
				case SKIPPED -> skipped++;
				case CACHED -> cached++;
				case BODY_TOO_LARGE -> {
					failed++;
					bodyTooLarge++;
				}
			}
		}

		synchronized ResponseTotals totals() {
			return new ResponseTotals(ok, failed, timeout, skipped, okMsTotal, okMsMax, cached, bodyTooLarge);
		}

	}

}
