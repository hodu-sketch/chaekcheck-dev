package com.chaekcheck.popular.internal.schedule;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.popular.internal.PopularProperties;
import com.chaekcheck.popular.internal.RankingChanged;
import com.chaekcheck.popular.internal.fetch.FetchFailure;
import com.chaekcheck.popular.internal.fetch.KpipaClient;
import com.chaekcheck.popular.internal.fetch.KpipaTransport;
import com.chaekcheck.popular.internal.fetch.MonthRanking;
import com.chaekcheck.popular.internal.fetch.RequestPacer;
import com.chaekcheck.popular.internal.store.PopularStore;

/**
 * One fetch of a month (plan cycle 10 design-delta "주요 흐름 1"): the ranking and sales counts, stored with a
 * SUCCEEDED run in one transaction, or one FAILED run and {@code log.error} (AC-11); then the covers not stored yet,
 * 2 seconds apart, the covers no ranking of the latest two months uses deleted, the cover counts of the run updated
 * and the cache told. Some covers failing does not fail the month (ADR-0075). If the server stops while covers are
 * fetched, the ranking and its SUCCEEDED run stay with cover counts 0; the next fetch gets the missing covers.
 * <p>
 * Fetches never overlap ({@link AtomicBoolean}, plan design #7); they run on the module's own single thread
 * ({@link FetchScheduler}), never on the shared scheduling thread.
 */
public class FetchJob {

	private static final Logger log = LoggerFactory.getLogger(FetchJob.class);

	private final FetchDecision decision;

	private final PopularStore store;

	private final KpipaTransport transport;

	private final PopularProperties.Fetch settings;

	private final RequestPacer.Ticker ticker;

	private final Clock clock;

	private final List<RankingChanged> listeners;

	private final AtomicBoolean running = new AtomicBoolean();

	public FetchJob(FetchDecision decision, PopularStore store, KpipaTransport transport,
			PopularProperties.Fetch settings, RequestPacer.Ticker ticker, Clock clock, List<RankingChanged> listeners) {
		this.decision = decision;
		this.store = store;
		this.transport = transport;
		this.settings = settings;
		this.ticker = ticker;
		this.clock = clock;
		this.listeners = List.copyOf(listeners);
	}

	/**
	 * Fetches the month the decision names, if any and if no fetch is running.
	 * @return the month fetched (successfully or not), or empty when nothing was due or a fetch was running
	 */
	public Optional<YearMonth> runIfDue(FetchTrigger trigger) {
		if (!running.compareAndSet(false, true)) {
			log.info("popular fetch ({}) skipped: a fetch is running", trigger);
			return Optional.empty();
		}
		try {
			Optional<YearMonth> month = decision.monthToFetch();
			if (month.isEmpty()) {
				log.info("popular fetch ({}): nothing due", trigger);
				return Optional.empty();
			}
			fetch(month.get(), trigger);
			return month;
		}
		catch (RuntimeException ex) {
			log.error("popular fetch ({}) stopped", trigger, ex);
			return Optional.empty();
		}
		finally {
			running.set(false);
		}
	}

	/** Fetches {@code month} now. */
	public void fetch(YearMonth month, FetchTrigger trigger) {
		Instant startedAt = clock.instant();
		log.info("popular fetch of {} started ({})", month, trigger);
		try (KpipaTransport.Connection connection = transport.open()) {
			KpipaClient client = new KpipaClient(connection, new RequestPacer(settings.requestInterval(), ticker),
					settings.baseUrl().toString());
			MonthRanking ranking;
			try {
				ranking = client.fetchRanking(month);
			}
			catch (FetchFailure failure) {
				store.saveFailed(month, trigger.name(), startedAt, clock.instant(), failure, client.requestCount());
				log.error("popular fetch of {} failed: {} ({}) after {} requests; the month shown stays", month,
						failure.reason().value(), failure.getMessage(), client.requestCount());
				return;
			}
			long runId = store.saveSucceeded(ranking, trigger.name(), startedAt, clock.instant());
			log.info("popular fetch of {} stored: {} books, {} without ISBN, {} requests", month, ranking.bookCount(),
					ranking.missingIsbnCount(), ranking.requestCount());
			tellListeners();
			fetchCovers(client, month, runId);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			log.warn("popular fetch of {} interrupted", month);
		}
	}

	private void fetchCovers(KpipaClient client, YearMonth month, long runId) throws InterruptedException {
		List<String> paths = store.missingCoverPaths(month);
		int fetched = 0;
		int failed = 0;
		for (String path : paths) {
			Optional<KpipaClient.CoverImage> cover = client.fetchCover(path);
			if (cover.isPresent()) {
				store.saveCover(path, cover.get().contentType(), cover.get().image(), clock.instant());
				fetched++;
			}
			else {
				failed++;
			}
		}
		int deleted = store.deleteUnusedCovers();
		store.updateCoverCounts(runId, fetched, failed);
		log.info("popular covers of {}: {} new, {} failed, {} unused deleted", month, fetched, failed, deleted);
		tellListeners();
	}

	private void tellListeners() {
		listeners.forEach(RankingChanged::rankingChanged);
	}

}
