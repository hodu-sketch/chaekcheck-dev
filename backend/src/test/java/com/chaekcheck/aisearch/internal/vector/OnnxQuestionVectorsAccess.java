package com.chaekcheck.aisearch.internal.vector;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/** Lets tests outside this package make (and close) the package-private {@link OnnxQuestionVectors}. */
public final class OnnxQuestionVectorsAccess {

	private final AiSearchProperties properties;

	public OnnxQuestionVectorsAccess(AiSearchProperties properties) {
		this.properties = properties;
	}

	/** An open model; closing it closes the model. */
	public Opened open() {
		return new Opened(new OnnxQuestionVectors(properties));
	}

	public record Opened(OnnxQuestionVectors vectors) implements AutoCloseable {

		public QuestionVectors get() {
			return vectors;
		}

		@Override
		public void close() {
			vectors.close();
		}

	}

}
