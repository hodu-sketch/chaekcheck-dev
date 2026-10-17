package com.chaekcheck.availability.internal.web;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.chaekcheck.availability.internal.search.EventSink;

/**
 * Writes stream events as SSE ({@code event:} name + JSON {@code data:}). No event ids (no reconnect resume). The
 * emitter's error and timeout callbacks tell the session the client is gone (cycle 20, ADR-0131), so a batch stops
 * starting libraries even between two sends.
 */
class SseEventSink implements EventSink {

	private final SseEmitter emitter;

	SseEventSink(SseEmitter emitter) {
		this.emitter = emitter;
	}

	@Override
	public boolean send(String name, Object data) {
		try {
			emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
			return true;
		}
		catch (IOException | IllegalStateException ex) {
			// client disconnected or the emitter already completed/timed out
			return false;
		}
	}

	@Override
	public void complete() {
		emitter.complete();
	}

	@Override
	public void onClientGone(Runnable listener) {
		emitter.onError(ex -> listener.run());
		emitter.onTimeout(listener);
	}

}
