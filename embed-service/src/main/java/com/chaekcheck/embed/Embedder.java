package com.chaekcheck.embed;

/** Turns a text into its normalised vector ({@link OnnxEmbedder}; a fake in the controller tests). */
public interface Embedder {

	/** Whether the model is loaded. */
	boolean ready();

	float[] embed(String text);

}
