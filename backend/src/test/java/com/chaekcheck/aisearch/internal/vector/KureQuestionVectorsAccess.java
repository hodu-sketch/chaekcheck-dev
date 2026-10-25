package com.chaekcheck.aisearch.internal.vector;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/** Lets tests outside this package make (and close) the package-private {@link KureQuestionVectors}. */
public final class KureQuestionVectorsAccess {

	private final AiSearchProperties properties;

	public KureQuestionVectorsAccess(AiSearchProperties properties) {
		this.properties = properties;
	}

	/** An open model; closing it closes the model. */
	public Opened open() {
		return new Opened(new KureQuestionVectors(properties, tools.jackson.databind.json.JsonMapper.builder().build()));
	}

	public record Opened(KureQuestionVectors vectors) implements AutoCloseable {

		public QuestionVectors get() {
			return vectors;
		}

		@Override
		public void close() {
			vectors.close();
		}

	}

}
