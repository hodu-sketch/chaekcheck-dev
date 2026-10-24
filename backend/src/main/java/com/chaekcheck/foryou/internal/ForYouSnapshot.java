package com.chaekcheck.foryou.internal;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.chaekcheck.foryou.internal.store.ForYouStore;
import com.chaekcheck.foryou.internal.store.ForYouStore.LastBuild;

/**
 * The last succeeded build in memory: read after each build, at start and every {@code refresh-interval} (like the
 * picks cache). A failed read keeps the copy before.
 */
public class ForYouSnapshot {

	private static final Logger log = LoggerFactory.getLogger(ForYouSnapshot.class);

	private final ForYouStore store;

	private volatile LastBuild current;

	public ForYouSnapshot(ForYouStore store) {
		this.store = store;
	}

	/** Empty until a build has succeeded once. */
	public Optional<LastBuild> current() {
		return Optional.ofNullable(current);
	}

	@Scheduled(initialDelay = 0, fixedDelayString = "${chaekcheck.foryou.build.refresh-interval}")
	public void refresh() {
		try {
			current = store.lastSucceeded().orElse(null);
		}
		catch (RuntimeException ex) {
			log.warn("foryou snapshot refresh failed, the copy before stays: {}", ex.toString());
		}
	}

}
