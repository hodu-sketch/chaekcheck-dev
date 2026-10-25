package com.chaekcheck.aisearch.internal.vector;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The books whose vectors are nearest the question's (user 2026-10-25, docs/reports/ai-search-eval-2026-10-25.md):
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
