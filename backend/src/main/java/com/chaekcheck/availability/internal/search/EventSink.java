package com.chaekcheck.availability.internal.search;

/**
 * Where stream events go. The SSE implementation wraps an {@code SseEmitter}; tests collect events in a list.
 * Calls are serialized by {@link BatchSession}.
 */
public interface EventSink {

	/** @return false when the client is gone; later events are dropped */
	boolean send(String name, Object data);

	void complete();

	/**
	 * Called once by the session: {@code listener} runs when the sink learns on its own that the client is gone (the
	 * SSE emitter's error or timeout callback, cycle 20 ADR-0131). A sink that only learns it from a failed
	 * {@link #send} keeps the default.
	 */
	default void onClientGone(Runnable listener) {
	}

}
