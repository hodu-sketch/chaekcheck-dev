package com.chaekcheck.foryou.internal.build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts the build when {@code chaekcheck.foryou.build.enabled} is true (only then is this bean made): at start
 * (failing what was left running, building when today has no list) and every day at {@code cron}. Both hand the work
 * to the module's own single thread, so neither the start-up nor Spring's scheduling thread waits for the sites.
 */
public class BuildScheduler implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(BuildScheduler.class);

	private final ForYouBuild build;

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(Thread.ofPlatform().name("foryou-build").daemon(true).factory());

	public BuildScheduler(ForYouBuild build) {
		this.build = build;
	}

	@EventListener(ApplicationReadyEvent.class)
	void atStartup() {
		log.info("foryou build check (start-up) handed to the build thread");
		executor.execute(build::runAtStart);
	}

	@Scheduled(cron = "${chaekcheck.foryou.build.cron}", zone = "${chaekcheck.foryou.build.zone}")
	void daily() {
		log.info("foryou daily build handed to the build thread");
		executor.execute(build::build);
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

}
