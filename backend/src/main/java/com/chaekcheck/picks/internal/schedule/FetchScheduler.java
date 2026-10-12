package com.chaekcheck.picks.internal.schedule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts fetch decisions when {@code chaekcheck.picks.fetch.enabled} is true (only then is this bean made): once when
 * the application is ready (AC-13, AC-13b) and every day at {@code cron} (ADR-0092). Both only hand the work to the
 * module's own single thread, so neither the start-up nor Spring's one scheduling thread waits for a fetch (plan cycle
 * 14 C8). The thread is stopped (interrupted) when the application stops.
 */
public class FetchScheduler implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(FetchScheduler.class);

	private final FetchJob job;

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(Thread.ofPlatform().name("picks-fetch").daemon(true).factory());

	public FetchScheduler(FetchJob job) {
		this.job = job;
	}

	@EventListener(ApplicationReadyEvent.class)
	void atStartup() {
		submit(true);
	}

	@Scheduled(cron = "${chaekcheck.picks.fetch.cron}", zone = "${chaekcheck.picks.fetch.zone}")
	void daily() {
		submit(false);
	}

	private void submit(boolean atStartup) {
		log.info("picks fetch decision ({}) handed to the fetch thread", atStartup ? "start-up" : "05:00");
		executor.execute(() -> job.runIfDue(atStartup));
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

}
