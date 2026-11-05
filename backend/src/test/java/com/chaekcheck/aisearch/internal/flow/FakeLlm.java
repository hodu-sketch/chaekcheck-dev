package com.chaekcheck.aisearch.internal.flow;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import com.chaekcheck.aisearch.internal.llm.LlmClient;

/**
 * A programmable {@link LlmClient} for the flow tests: each call takes the next planned answer. A streamed answer is
 * handed on in the given pieces; it may then fail. Every call is kept so the tests can read what would be sent.
 */
public final class FakeLlm implements LlmClient {

	/** A planned answer: pieces, then the result or the failure. */
	public record Answer(List<String> pieces, Usage usage, Failure failure) {

		public static Answer ok(String text, int input, int output) {
			return new Answer(List.of(text), new Usage(input, 0, 0, output, true), null);
		}

		public static Answer pieces(List<String> pieces, int input, int output) {
			return new Answer(pieces, new Usage(input, 0, 0, output, true), null);
		}

		public static Answer failAfter(List<String> pieces, Failure failure, Usage usage) {
			return new Answer(pieces, usage, failure);
		}

	}

	public final Deque<Answer> planned = new ArrayDeque<>();

	public final List<LlmCall> calls = new ArrayList<>();

	public FakeLlm then(Answer answer) {
		planned.add(answer);
		return this;
	}

	@Override
	public synchronized Result complete(LlmCall call, Duration timeout) {
		calls.add(call);
		Answer answer = next();
		if (answer.failure() != null) {
			throw new LlmException(answer.failure(), answer.usage(), "fake");
		}
		return new Result(String.join("", answer.pieces()), answer.usage(), "end_turn");
	}

	@Override
	public synchronized Result stream(LlmCall call, Duration timeout, Consumer<String> onText) {
		calls.add(call);
		Answer answer = next();
		StringBuilder text = new StringBuilder();
		for (String piece : answer.pieces()) {
			text.append(piece);
			try {
				onText.accept(piece);
			}
			catch (RuntimeException e) {
				throw new LlmException(Failure.CANCELLED, new Usage(answer.usage().inputTokens(), 0, 0, 0, false), "fake");
			}
		}
		if (answer.failure() != null) {
			throw new LlmException(answer.failure(), answer.usage(), "fake");
		}
		return new Result(text.toString(), answer.usage(), "end_turn");
	}

	private Answer next() {
		Answer answer = planned.poll();
		if (answer == null) {
			throw new LlmException(Failure.NOT_CONFIGURED, Usage.NONE, "no planned answer");
		}
		return answer;
	}

}
