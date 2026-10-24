package com.chaekcheck.aisearch.internal.flow;

/** Where the flow writes its stream events (an SSE emitter in the server, a list in tests). */
public interface AiSearchSink {

	/** @return false when the client is gone; nothing more is sent after that */
	boolean send(String event, Object data);

	/** Whether the client is gone (told by the transport even between two sends). */
	default boolean gone() {
		return false;
	}

	/** Ends the stream. */
	void complete();

}
