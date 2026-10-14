package com.chaekcheck.usagelog.internal.write;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.usagelog.internal.buffer.UsageBuffer;
import com.chaekcheck.usagelog.internal.buffer.UsageRow;

/**
 * Empties the buffer every 5 seconds on its own thread (ADR-0097), so a slow write never waits on or holds up the
 * other scheduled jobs. Library numbers from the web are checked once per batch against the enabled libraries; an
 * unknown or disabled one becomes a {@code rejected_event} ({@code unknown_library}, plan C5).
 * <p>
 * A failed write drops its batch, logs one WARN line and remembers the loss for {@code dropped_batch}, written with the
 * next batch that succeeds. On a normal shutdown the rows left are written: this stops after the web server has stopped
 * taking requests and before the data source is closed (spec R3-4).
 */
public class UsageLogFlusher implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(UsageLogFlusher.class);

	/**
	 * Below the web server's lifecycle (Spring Boot: graceful shutdown at {@code DEFAULT_PHASE - 1024}, start/stop at
	 * {@code DEFAULT_PHASE - 2048}), so this stops after it. The data source is closed later, with the beans.
	 */
	static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

	private static final Duration STOP_WAIT = Duration.ofSeconds(10);

	private final UsageBuffer buffer;

	private final UsageLogWriter writer;

	private final LibraryCatalog libraryCatalog;

	private final Clock clock;

	private final Duration interval;

	private final int maxDroppedNotes;

	private final boolean scheduled;

	/** Losses not written yet; touched only by the flush (one thread, or the stop after it). */
	private final List<UsageLogWriter.Dropped> pendingDropped = new ArrayList<>();

	private volatile ScheduledExecutorService executor;

	private volatile boolean running;

	/**
	 * @param scheduled false: no thread (logging off); {@link #flush()} can still be called
	 */
	public UsageLogFlusher(UsageBuffer buffer, UsageLogWriter writer, LibraryCatalog libraryCatalog, Clock clock,
			Duration interval, int maxDroppedNotes, boolean scheduled) {
		this.buffer = buffer;
		this.writer = writer;
		this.libraryCatalog = libraryCatalog;
		this.clock = clock;
		this.interval = interval;
		this.maxDroppedNotes = maxDroppedNotes;
		this.scheduled = scheduled;
	}

	/** Writes what is waiting now. Never throws. */
	public synchronized void flush() {
		UsageBuffer.Batch batch = buffer.drain();
		List<UsageLogWriter.Dropped> dropped = new ArrayList<>(pendingDropped);
		if (batch.droppedWhileFull() > 0) {
			dropped.add(new UsageLogWriter.Dropped(clock.instant(), batch.droppedWhileFull(), "buffer_full"));
		}
		if (batch.rows().isEmpty() && batch.responses().isEmpty() && dropped.isEmpty()) {
			return;
		}
		try {
			writer.write(checkLibraries(batch.rows()), batch.responses(), dropped);
			pendingDropped.clear();
		}
		catch (RuntimeException ex) {
			// The batch is given up, never retried (ADR-0097); the cause is enough to look into, the rows are not logged
			log.warn("Usage log write failed, {} rows dropped: {}", batch.rowCount(), ex.toString());
			pendingDropped.clear();
			pendingDropped.addAll(dropped);
			if (batch.rowCount() > 0) {
				pendingDropped.add(new UsageLogWriter.Dropped(clock.instant(), batch.rowCount(), "db_failure"));
			}
			limitPending();
		}
	}

	private List<UsageRow> checkLibraries(List<UsageRow> rows) {
		if (rows.stream().noneMatch(row -> row instanceof UsageRow.NamesLibrary named && named.libraryId() != null)) {
			return rows;
		}
		Set<Long> enabled = new HashSet<>();
		for (LibraryRef ref : libraryCatalog.enabledLibraryRefs()) {
			enabled.add(ref.id());
		}
		List<UsageRow> checked = new ArrayList<>(rows.size());
		for (UsageRow row : rows) {
			checked.add(switch (row) {
				case UsageRow.LibraryClick click when !enabled.contains(click.libraryId()) ->
					new UsageRow.Rejected(click.receivedAt(), click.clientIp(), "unknown_library", click.raw());
				case UsageRow.ScreenEvent screen when screen.libraryId() != null
						&& !enabled.contains(screen.libraryId()) ->
					new UsageRow.Rejected(screen.receivedAt(), screen.clientIp(), "unknown_library", screen.raw());
				default -> row;
			});
		}
		return checked;
	}

	/** Keeps at most {@code maxDroppedNotes} notes: the oldest is added to the next one with the same reason. */
	private void limitPending() {
		while (pendingDropped.size() > maxDroppedNotes) {
			UsageLogWriter.Dropped oldest = pendingDropped.removeFirst();
			for (int i = 0; i < pendingDropped.size(); i++) {
				UsageLogWriter.Dropped next = pendingDropped.get(i);
				if (next.reason().equals(oldest.reason())) {
					pendingDropped.set(i,
							new UsageLogWriter.Dropped(next.droppedAt(), next.rowCount() + oldest.rowCount(), next.reason()));
					oldest = null;
					break;
				}
			}
			if (oldest != null) {
				log.warn("Usage log forgot a dropped note: {} rows, {}", oldest.rowCount(), oldest.reason());
			}
		}
	}

	@Override
	public void start() {
		running = true;
		if (!scheduled || executor != null) {
			return;
		}
		ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "usagelog-flush");
			thread.setDaemon(true);
			return thread;
		});
		long millis = interval.toMillis();
		started.scheduleWithFixedDelay(this::flush, millis, millis, TimeUnit.MILLISECONDS);
		executor = started;
	}

	@Override
	public void stop() {
		running = false;
		ScheduledExecutorService started = executor;
		executor = null;
		if (started != null) {
			started.shutdown();
			try {
				if (!started.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
					log.warn("Usage log flush did not stop within {}", STOP_WAIT);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		flush();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}

}
