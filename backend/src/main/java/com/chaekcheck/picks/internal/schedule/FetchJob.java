package com.chaekcheck.picks.internal.schedule;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.picks.internal.PicksChanged;
import com.chaekcheck.picks.internal.PicksProperties;
import com.chaekcheck.picks.internal.fetch.FailureReason;
import com.chaekcheck.picks.internal.fetch.FetchFailure;
import com.chaekcheck.picks.internal.fetch.KeptPicks;
import com.chaekcheck.picks.internal.fetch.NlkClient;
import com.chaekcheck.picks.internal.fetch.NlkTransport;
import com.chaekcheck.picks.internal.fetch.RequestPacer;
import com.chaekcheck.picks.internal.fetch.SaseoXmlParser;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * One fetch (plan cycle 14 design-delta "주요 흐름: 받기"): the list with one request, the latest twelve months kept
 * ({@link KeptPicks}) and stored with a SUCCEEDED run in one transaction, or one FAILED run and {@code log.error}
 * (AC-11). When the list changed the cache is told. No covers are fetched since cycle 26 deploy 2 (ADR-0166): the
 * PC uploads new list pictures to S3 as sha1(cover_source).jpg (ADR-0163).
 * <p>
 * Fetches never overlap ({@link AtomicBoolean}, plan cycle 14 C8); they run on the module's own single thread
 * ({@link FetchScheduler}), never on the shared scheduling thread.
 */
public class FetchJob {

	private static final Logger log = LoggerFactory.getLogger(FetchJob.class);

	private final FetchDecision decision;

	private final PicksStore store;

	private final NlkTransport transport;

	private final PicksProperties.Fetch settings;

	private final RequestPacer.Ticker ticker;

	private final Clock clock;

	private final List<PicksChanged> listeners;

	private final AtomicBoolean running = new AtomicBoolean();

	public FetchJob(FetchDecision decision, PicksStore store, NlkTransport transport, PicksProperties.Fetch settings,
			RequestPacer.Ticker ticker, Clock clock, List<PicksChanged> listeners) {
		this.decision = decision;
		this.store = store;
		this.transport = transport;
		this.settings = settings;
		this.ticker = ticker;
		this.clock = clock;
		this.listeners = List.copyOf(listeners);
	}

	/**
	 * Fetches if the decision says so and no fetch is running.
	 * @return the trigger fetched with (successfully or not), or empty when nothing was due or a fetch was running
	 */
	public Optional<FetchTrigger> runIfDue(boolean atStartup) {
		String when = atStartup ? "start-up" : "05:00";
		if (!running.compareAndSet(false, true)) {
			log.info("picks fetch ({}) skipped: a fetch is running", when);
			return Optional.empty();
		}
		try {
			Optional<FetchTrigger> trigger = decision.decide(atStartup);
			if (trigger.isEmpty()) {
				log.info("picks fetch ({}): nothing due", when);
				return Optional.empty();
			}
			fetch(trigger.get());
			return trigger;
		}
		catch (RuntimeException ex) {
			// no stack trace: a message deep in it could hold the request address and so the key (spec R1-7)
			log.error("picks fetch ({}) stopped: {}", when, masked(ex));
			return Optional.empty();
		}
		finally {
			running.set(false);
		}
	}

	/** Fetches now. */
	public void fetch(FetchTrigger trigger) {
		Instant startedAt = clock.instant();
		LocalDate today = LocalDate.ofInstant(startedAt, settings.zone());
		log.info("picks fetch started ({})", trigger);
		NlkClient client = new NlkClient(transport, new RequestPacer(settings.requestInterval(), ticker), settings);
		int received = 0;
		try {
			KeptPicks kept;
			try {
				SaseoXmlParser.SaseoList list = SaseoXmlParser.parse(client.fetchList(today));
				received = list.rows().size();
				kept = KeptPicks.of(list.rows());
			}
			catch (FetchFailure failure) {
				store.saveFailed(trigger.name(), startedAt, clock.instant(), failure, client.requestCount(), received);
				if (failure.reason() == FailureReason.KEY_MISSING) {
					log.warn("picks fetch ({}) not sent: no API key (CHAEKCHECK_PICKS_NLK_API_KEY)", trigger);
				}
				log.error("picks fetch ({}) failed: {} ({}) after {} requests; the list shown stays", trigger,
						failure.reason().value(), failure.getMessage(), client.requestCount());
				return;
			}
			PicksStore.Saved saved = store.saveSucceeded(kept, trigger.name(), startedAt, clock.instant(),
					client.requestCount());
			log.info("picks fetch ({}) stored: {} received, {} kept, {} without ISBN, new field codes {}, changed {}",
					trigger, kept.receivedCount(), kept.rows().size(), kept.missingIsbnCount(),
					kept.newCategoryCodes() == null ? "none" : kept.newCategoryCodes(), saved.changed());
			if (saved.changed()) {
				tellListeners();
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			log.warn("picks fetch ({}) interrupted", trigger);
		}
	}

	/** The exception's class and message with the key replaced. */
	private String masked(Throwable ex) {
		String text = ex.getClass().getName() + ": " + ex.getMessage();
		return settings.hasApiKey() ? text.replace(settings.apiKey().strip(), "***") : text;
	}

	private void tellListeners() {
		listeners.forEach(PicksChanged::picksChanged);
	}

}
