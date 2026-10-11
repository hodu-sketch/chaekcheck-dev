package com.chaekcheck.popular.internal.schedule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts fetches when {@code chaekcheck.popular.fetch.enabled} is true (only then is this bean made): once when the
 * application is ready (STARTUP, AC-13) and every day at {@code cron} (SCHEDULED). Both only hand the work to the
 * module's own single thread, so neither the start-up nor Spring's one scheduling thread (shared with the 04:00
 * clean-up) waits for a fetch of up to about 28 minutes (plan cycle 10 C9). The thread is stopped (interrupted) when
 * the application stops.
 */
public class FetchScheduler implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(FetchScheduler.class);

	private final FetchJob job;

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(Thread.ofPlatform().name("popular-fetch").daemon(true).factory());

	public FetchScheduler(FetchJob job) {
		this.job = job;
	}

	@EventListener(ApplicationReadyEvent.class)
	void atStartup() {
		submit(FetchTrigger.STARTUP);
	}

	@Scheduled(cron = "${chaekcheck.popular.fetch.cron}", zone = "${chaekcheck.popular.fetch.zone}")
	void daily() {
		submit(FetchTrigger.SCHEDULED);
	}

	private void submit(FetchTrigger trigger) {
		log.info("popular fetch decision ({}) handed to the fetch thread", trigger);
		executor.execute(() -> job.runIfDue(trigger));
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

}
