package com.chaekcheck.aisearch.internal.vector;

/**
 * Turns a question into its normalised KURE-v1 vector: {@link OnnxQuestionEncoder} in this process or
 * {@link RemoteQuestionEncoder} on the embedding server (user 2026-10-25, chosen by
 * {@code chaekcheck.aisearch.vector.encoder}).
 */
interface QuestionEncoder extends AutoCloseable {

	/** The vector; throws when the model run or the request fails (the search then goes on with keywords). */
	float[] encode(String question);

	@Override
	default void close() {
	}

}
