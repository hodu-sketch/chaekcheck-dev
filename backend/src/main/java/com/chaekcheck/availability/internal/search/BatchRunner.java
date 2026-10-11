package com.chaekcheck.availability.internal.search;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.ParsedDetail;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolvers;
import com.chaekcheck.availability.internal.resolve.DetailPageFetcher;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;

/**
 * Runs one batch of a search (ADR-0014, design-delta "묶음 실행 규칙"). The server keeps no search state: the
 * browser sends the candidates of every batch after the first.
 * <ul>
 * <li>Requests start in candidate order, at most {@code parallelism} at a time.</li>
 * <li>A candidate whose host already has a request of this batch in flight, or had one end less than
 * {@code hostMinInterval} ago, is skipped for now and the next candidate starts; skipped candidates start first, in
 * candidate order, once their host is free (plan-feedback C2-U-12). So one host never holds several slots while it
 * waits for the host throttle.</li>
 * <li>Once more than {@code rowLimit} rows are counted, no new request starts; started ones are still
 * received.</li>
 * <li>Search pages must answer within {@code deadline} of the batch start (else upstream_timeout); no request
 * starts when less than {@code launchCutoff} is left.</li>
 * <li>Candidates not started by the launch cutoff are not requested and do not fail: the browser keeps them as not
 * asked and sends them in the next batch.</li>
 * <li>The host throttle still guards every call, also against other searches on the same host.</li>
 * </ul>
 * Every batch takes a {@link SearchSlots} slot first and gives it back when its stream is done (or at the latest
 * {@link #SLOT_SAFETY_MARGIN} after the longest stream time); with no free slot it is refused with
 * {@link SearchBusyException} before any event is sent.
 * <p>
 * The runner knows nothing about screen kinds or detail lookups: it resolves each page with the resolver for its
 * screen kind and waits until that library's delivery is done (ADR-0019).
 */
public class BatchRunner {

	private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

	/**
	 * A slot is given back at the latest this long after {@link #streamDuration()}, even if the session never
	 * finished (a bug or a lost future). Not a tuning value: a safety net so a slot can never leak.
	 */
	static final Duration SLOT_SAFETY_MARGIN = Duration.ofSeconds(10);

	private final LibraryCatalog libraryCatalog;

	private final LibraryPageFetcher fetcher;

	private final DetailPageFetcher detailPages;

	private final AvailabilityResolvers resolvers;

	private final AvailabilityProperties.Batch settings;

	private final Duration detailDeadline;

	private final Duration hostMinInterval;

	private final Executor executor;

	private final SearchSlots slots;

	private final Clock clock;

	public BatchRunner(LibraryCatalog libraryCatalog, LibraryPageFetcher fetcher, DetailPageFetcher detailPages,
			AvailabilityResolvers resolvers, AvailabilityProperties.Batch settings, Duration detailDeadline,
			Duration hostMinInterval, Executor executor, SearchSlots slots, Clock clock) {
		this.libraryCatalog = libraryCatalog;
		this.fetcher = fetcher;
		this.detailPages = detailPages;
		this.resolvers = resolvers;
		this.settings = settings;
		this.detailDeadline = detailDeadline;
		this.hostMinInterval = hostMinInterval;
		this.executor = executor;
		this.slots = slots;
		this.clock = clock;
	}

	/**
	 * The search plan. Call before opening the stream so region errors become a normal 422 response.
	 * @throws com.chaekcheck.library.InvalidRegionException for abolished, unknown or SIDO ids
	 */
	public List<SearchTarget> plan(Set<Long> residenceIds, Set<Long> workIds, PlanOptions options) {
		return libraryCatalog.planSearch(residenceIds, workIds, options);
	}

	public Optional<SearchTarget> target(long libraryId) {
		return libraryCatalog.findEnabledTarget(libraryId);
	}

	public int maxCandidates() {
		return settings.maxCandidates();
	}

	/** Longest a batch stream stays open: the detail deadline (row verdicts come last). */
	public Duration streamDuration() {
		return detailDeadline.compareTo(settings.deadline()) > 0 ? detailDeadline : settings.deadline();
	}

	/**
	 * Starts a batch and returns at once; events go to {@code sink}.
	 * @throws SearchBusyException when every search slot is taken; nothing was sent
	 * @param targets the targets event (first stream only), or null
	 * @param notFound candidates whose library is missing or disabled; each fails on its own
	 */
	public void start(EventSink sink, SearchQuery query, ResultFilter filter, int batchNo, List<Candidate> candidates,
			List<MissingCandidate> notFound, StreamEvents.Targets targets) {
		SearchSlots.Slot slot = slots.acquire();
		try {
			startSearch(slot, sink, query, filter, batchNo, candidates, notFound, targets);
		}
		catch (RuntimeException ex) {
			slot.close();
			throw ex;
		}
	}

	private void startSearch(SearchSlots.Slot slot, EventSink sink, SearchQuery query, ResultFilter filter,
			int batchNo, List<Candidate> candidates, List<MissingCandidate> notFound, StreamEvents.Targets targets) {
		BatchSession session = new BatchSession(sink, batchNo, filter, settings.rowLimit(), slot::close);
		releaseLater(slot);
		if (targets != null) {
			session.targets(targets);
		}
		notFound.forEach(missing -> session.notFound(missing.libraryId(), missing.page()));
		int planSize = targets == null ? -1 : targets.libraries().size();
		executor.execute(() -> {
			Instant start = clock.instant();
			Instant deadline = start.plus(settings.deadline());
			Instant detailBy = start.plus(detailDeadline);
			StreamEvents.StopReason reason = runTasks(session, candidates, deadline, candidate -> {
				SearchTarget target = candidate.target();
				return fetcher.fetchAsync(target, query, candidate.page(), deadline).whenComplete((page, ex) -> {
					// count the rows before the slot is freed (chained after this), so the next launch sees them
					try {
						if (ex != null) {
							session.failed(candidate, asUpstream(ex));
							return;
						}
						AvailabilityResolver resolver = resolvers.forPlatform(target.platformType());
						session.succeeded(candidate, resolver.resolve(target, page, detailBy));
					}
					catch (RuntimeException resolveFailure) {
						session.failed(candidate, asUpstream(resolveFailure));
					}
				});
			});
			log.info("batch={} planSize={} candidates={} requested={} stopReason={} startedAt={}", batchNo,
					planSize < 0 ? "-" : planSize, candidates.size(), session.requestedCount(), reason, start);
		});
	}

	/**
	 * Starts a holding batch (cycle 04 holding stream) and returns at once; events go to {@code sink}. Same rules as
	 * search pages: parallelism, busy-host skipping, row limit, batch deadline, launch cutoff (ADR-0029). Subscription
	 * holdings are decided without a request [확정]; a detail URL off the library's host fails without a request [안].
	 * @throws SearchBusyException when every search slot is taken; nothing was sent
	 * @param targets the targets event (first stream only), or null
	 * @param notFound holding ids that do not exist or whose library is not usable; each fails on its own
	 */
	public void startHoldings(EventSink sink, ResultFilter filter, int batchNo, List<HoldingTask> tasks,
			List<MissingHolding> notFound, StreamEvents.HoldingTargets targets) {
		SearchSlots.Slot slot = slots.acquire();
		try {
			startHoldingBatch(slot, sink, filter, batchNo, tasks, notFound, targets);
		}
		catch (RuntimeException ex) {
			slot.close();
			throw ex;
		}
	}

	private void startHoldingBatch(SearchSlots.Slot slot, EventSink sink, ResultFilter filter, int batchNo,
			List<HoldingTask> tasks, List<MissingHolding> notFound, StreamEvents.HoldingTargets targets) {
		HoldingSession session = new HoldingSession(sink, batchNo, filter, settings.rowLimit(), slot::close);
		releaseLater(slot);
		if (targets != null) {
			session.targets(targets);
		}
		notFound.forEach(session::notFound);
		executor.execute(() -> {
			Instant start = clock.instant();
			Instant deadline = start.plus(settings.deadline());
			StreamEvents.StopReason reason = runTasks(session, tasks, deadline, task -> startHolding(session, batchNo, task,
					deadline));
			log.info("holding batch={} holdings={} requested={} stopReason={} startedAt={}", batchNo, tasks.size(),
					session.requestedCount(), reason, start);
		});
	}

	/** Safety net: gives the slot back even if the session never reports that it finished. */
	private void releaseLater(SearchSlots.Slot slot) {
		long delay = streamDuration().plus(SLOT_SAFETY_MARGIN).toMillis();
		CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(slot::close);
	}

	private CompletableFuture<?> startHolding(HoldingSession session, int batchNo, HoldingTask task, Instant deadline) {
		if (task.subscription()) {
			session.succeeded(task, holdingResult(batchNo, task, resolvers.subscription(), null, clock.instant(), false));
			return CompletableFuture.completedFuture(null);
		}
		if (task.detailUri() == null) {
			session.failed(task, UpstreamException.error("detail URL is not an http(s) page on the library's host"));
			return CompletableFuture.completedFuture(null);
		}
		Instant requestedAt = clock.instant();
		return detailPages.fetch(task.target(), task.detailUri(), deadline).whenComplete((page, ex) -> {
			try {
				if (ex != null) {
					session.failed(task, asUpstream(ex));
					return;
				}
				AvailabilityResolver.Decision decision = resolvers.decideDetail(task.target().platformType(),
						page.detail());
				session.succeeded(task, holdingResult(batchNo, task, decision, page.detail(), page.fetchedAt(),
						page.fetchedAt().isBefore(requestedAt)));
			}
			catch (RuntimeException readFailure) {
				session.failed(task, asUpstream(readFailure));
			}
		});
	}

	private static StreamEvents.HoldingResult holdingResult(int batchNo, HoldingTask task, AvailabilityResolver.Decision decision,
			ParsedDetail detail, Instant fetchedAt, boolean cached) {
		HoldingRef holding = task.holding();
		Integer holdings = detail == null ? null : detail.holdings();
		Integer loaned = detail == null ? null : detail.loaned();
		Integer available = holdings == null || loaned == null ? null : holdings - loaned;
		LibraryPage.Item item = new LibraryPage.Item(0, holding.title(), holding.author(), holding.publisher(), null,
				holdings, loaned, detail == null ? null : detail.reservations(), available, "book:" + holding.bookId(),
				decision.availability(), decision.basis(), holding.detailUrl(), null);
		return new StreamEvents.HoldingResult(batchNo, holding.holdingId(), holding.bookId(),
				holding.libraryId(), item, fetchedAt, cached);
	}

	/**
	 * The launch loop shared by search pages and holdings. Tasks start in order while a slot is free and their host is
	 * free; no task starts once the row limit is passed or the cutoff is reached; the session fails what is still in
	 * flight at the deadline.
	 * @param start sends one task; its future must complete after the session counted the task's rows
	 */
	private <T extends LookupTask> StreamEvents.StopReason runTasks(LaunchSession<T> session, List<T> tasks,
			Instant deadline, Function<T, CompletableFuture<?>> start) {
		Instant cutoff = deadline.minus(settings.launchCutoff());
		long untilDeadline = Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
		CompletableFuture.runAsync(session::deadlinePassed,
				CompletableFuture.delayedExecutor(untilDeadline, TimeUnit.MILLISECONDS, executor));
		Launches<T> launches = new Launches<>(settings.parallelism(), tasks);
		StreamEvents.StopReason reason = StreamEvents.StopReason.CANDIDATES_EXHAUSTED;
		while (launches.hasPending()) {
			if (session.rowLimitReached()) {
				reason = StreamEvents.StopReason.ROW_LIMIT;
				break;
			}
			T task = launches.next(cutoff);
			if (task == null) {
				reason = StreamEvents.StopReason.LAUNCH_CUTOFF;
				break;
			}
			if (session.rowLimitReached()) {
				launches.cancel(task);
				reason = StreamEvents.StopReason.ROW_LIMIT;
				break;
			}
			if (task.hostKey() != null) {
				session.requested(task);
			}
			start.apply(task).whenComplete((result, ex) -> launches.finished(task));
		}
		session.launchFinished(reason);
		return reason;
	}

	/** What the launch loop needs from a stream session. Implementations are synchronized. */
	interface LaunchSession<T extends LookupTask> {

		boolean rowLimitReached();

		/** A task with a request is about to start. */
		void requested(T task);

		/** Batch deadline: every requested task without an answer fails with upstream_timeout. */
		void deadlinePassed();

		/** No more tasks will start. */
		void launchFinished(StreamEvents.StopReason reason);

		int requestedCount();

	}

	/**
	 * Which task starts next: the first not-started task (in task order) whose host is free in this batch, while a
	 * slot is free. A host is busy while a request of this batch is in flight and for {@code hostMinInterval} after
	 * it ended. Tasks without a request ({@link LookupTask#hostKey()} null) start at once and take no slot.
	 */
	private final class Launches<T extends LookupTask> {

		private final ReentrantLock lock = new ReentrantLock();

		private final Condition changed = lock.newCondition();

		private final Deque<T> pending;

		/** host -> free again at; {@link Instant#MAX} while in flight */
		private final Map<String, Instant> hostBusyUntil = new HashMap<>();

		private int freeSlots;

		Launches(int parallelism, List<T> tasks) {
			this.freeSlots = parallelism;
			this.pending = new ArrayDeque<>(tasks);
		}

		boolean hasPending() {
			lock.lock();
			try {
				return !pending.isEmpty();
			}
			finally {
				lock.unlock();
			}
		}

		/** Waits for a startable task; null once the cutoff is reached. */
		T next(Instant cutoff) {
			lock.lock();
			try {
				while (true) {
					Instant now = clock.instant();
					if (!now.isBefore(cutoff)) {
						return null;
					}
					Instant wakeAt = cutoff;
					for (Iterator<T> it = pending.iterator(); it.hasNext();) {
						T task = it.next();
						String host = task.hostKey();
						if (host == null) {
							it.remove();
							return task;
						}
						if (freeSlots <= 0) {
							continue;
						}
						Instant busyUntil = hostBusyUntil.get(host);
						if (busyUntil == null || !busyUntil.isAfter(now)) {
							it.remove();
							freeSlots--;
							hostBusyUntil.put(host, Instant.MAX);
							return task;
						}
						if (busyUntil.isBefore(wakeAt)) {
							wakeAt = busyUntil;
						}
					}
					changed.awaitNanos(Math.max(1, Duration.between(now, wakeAt).toNanos()));
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return null;
			}
			finally {
				lock.unlock();
			}
		}

		/** The request ended: free its slot now, and its host after the interval. */
		void finished(T task) {
			release(task, clock.instant().plus(hostMinInterval));
		}

		/** Picked but not started. */
		void cancel(T task) {
			release(task, clock.instant());
		}

		private void release(T task, Instant hostFreeAt) {
			lock.lock();
			try {
				if (task.hostKey() != null) {
					freeSlots++;
					hostBusyUntil.put(task.hostKey(), hostFreeAt);
				}
				changed.signalAll();
			}
			finally {
				lock.unlock();
			}
		}

	}

	private static UpstreamException asUpstream(Throwable ex) {
		Throwable cause = ex;
		while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause instanceof UpstreamException upstream ? upstream
				: new UpstreamException(UpstreamException.Kind.ERROR, "lookup failed", cause);
	}

	/** A candidate that could not become a target: its library is missing or disabled. */
	public record MissingCandidate(long libraryId, int page) {
	}

}
