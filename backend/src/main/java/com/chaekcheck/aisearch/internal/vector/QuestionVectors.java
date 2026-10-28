package com.chaekcheck.aisearch.internal.vector;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The books whose vectors are nearest the question's (user 2026-10-25, docs/aisearch/reports/2026-10-25-eval.md):
 * the question is turned into a KURE-v1 vector and compared with every book vector, off the search's own thread so it
 * runs while the first call does.
 */
public interface QuestionVectors {

	/** Whether vectors are on and loaded; off, {@link #nearest} gives an empty list at once. */
	boolean on();

	/**
	 * The ids of the {@code limit} book vectors nearest the question, nearest first. Completes with an empty list
	 * when off; completes exceptionally when the model run fails (the search then goes on with keywords alone).
	 */
	CompletableFuture<List<Long>> nearest(String question, int limit);

	/**
	 * The texts' normalised vectors in the same order, for the ML first call (user 2026-10-26): the question, and for a
	 * follow-up the earlier question and the two joined. One lookup for the circuit breaker however many texts.
	 * Completes exceptionally with {@link Unavailable} when off or while the encoder is not asked, otherwise when a
	 * run fails.
	 */
	default CompletableFuture<List<float[]>> vectors(List<String> texts) {
		return CompletableFuture.failedFuture(new Unavailable());
	}

	/** The vectors are off or the encoder is not asked for a while: nothing failed this time. */
	final class Unavailable extends IllegalStateException {

		public Unavailable() {
			super("question vectors are off or not asked", null);
		}

	}

	/** The ids of the {@code limit} book vectors nearest the given vector, nearest first; empty when off. */
	default List<Long> nearest(float[] vector, int limit) {
		return List.of();
	}

	/** Off: what a search without the model folder uses. */
	QuestionVectors OFF = new QuestionVectors() {

		@Override
		public boolean on() {
			return false;
		}

		@Override
		public CompletableFuture<List<Long>> nearest(String question, int limit) {
			return CompletableFuture.completedFuture(List.of());
		}

	};

}
