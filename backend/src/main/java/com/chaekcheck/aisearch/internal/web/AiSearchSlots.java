package com.chaekcheck.aisearch.internal.web;

import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * AI searches running at once on this server, apart from the search slots (user 2026-10-24: "(나) 따로 상한 하나",
 * 20). A full server refuses before the stream opens (503 {@code server_busy}).
 */
@Component
class AiSearchSlots {

	private final Semaphore slots;

	AiSearchSlots(AiSearchProperties properties) {
		this.slots = new Semaphore(properties.maxConcurrent());
	}

	boolean tryAcquire() {
		return slots.tryAcquire();
	}

	void release() {
		slots.release();
	}

	int available() {
		return slots.availablePermits();
	}

}
