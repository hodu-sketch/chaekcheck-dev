package com.chaekcheck.aisearch.internal.flow;

/** Where the flow writes its stream events (an SSE emitter in the server, a list in tests). */
public interface AiSearchSink {

	/** @return false when the client is gone; nothing more is sent after that */
	boolean send(String event, Object data);

	/** Ends the stream. */
	void complete();

}
