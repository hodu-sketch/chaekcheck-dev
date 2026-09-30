package com.chaekcheck.availability.internal.search;

/**
 * Where stream events go. The SSE implementation wraps an {@code SseEmitter}; tests collect events in a list.
 * Calls are serialized by {@link BatchSession}.
 */
public interface EventSink {

	/** @return false when the client is gone; later events are dropped */
	boolean send(String name, Object data);

	void complete();

}
