package com.chaekcheck.aisearch.internal.llm;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one place the AI search calls a language model (cycle 24, ADR-0150, architect decision 6): Anthropic now, a
 * local or rented model later behind the same shape. Implementations never log or throw the API key or the call's
 * text.
 */
public interface LlmClient {

	/**
	 * One call, the whole answer at once.
	 * @param timeout the whole call, from sending to the last byte
	 * @throws LlmException on any failure, with the usage known so far
	 */
	Result complete(LlmCall call, Duration timeout);

	/**
	 * One call whose text arrives in pieces, handed to {@code onText} in order on the calling thread.
	 * @param timeout the whole call, from sending to the end of the stream
	 * @throws LlmException on any failure, with the usage known so far; text already handed on stays handed on
	 */
	Result stream(LlmCall call, Duration timeout, Consumer<String> onText);

	/**
	 * @param system the instructions
	 * @param messages the conversation, oldest first, ending with the user's
	 * @param jsonSchema the JSON schema the answer must follow (structured output), as JSON text
	 * @param maxTokens the answer's token cap
	 */
	record LlmCall(String system, List<Message> messages, String jsonSchema, int maxTokens) {

		public LlmCall {
			messages = List.copyOf(messages);
		}

	}

	/** One turn of the conversation. */
	record Message(Role role, String text) {
	}

	enum Role {

		USER("user"), ASSISTANT("assistant");

		private final String wire;

		Role(String wire) {
			this.wire = wire;
		}

		public String wire() {
			return wire;
		}

	}

	/**
	 * @param text the answer's text (for {@link #stream} the same text that was handed on)
	 * @param usage the tokens counted
	 * @param stopReason why the model stopped ({@code end_turn}, {@code max_tokens}, …)
	 */
	record Result(String text, Usage usage, String stopReason) {
	}

	/**
	 * @param inputTokens plain (uncached) input tokens, 0 when not known
	 * @param cacheReadTokens tokens read from the prompt cache (billed at 0.1× the input price)
	 * @param cacheCreationTokens tokens written into the prompt cache (billed at 1.25× the input price)
	 * @param outputTokens tokens written, 0 when not known
	 * @param outputKnown false when the call broke before the model reported its output (the cost cap then counts the
	 *     call's {@code maxTokens}, handoff step 7)
	 */
	record Usage(int inputTokens, int cacheReadTokens, int cacheCreationTokens, int outputTokens, boolean outputKnown) {

		public static final Usage NONE = new Usage(0, 0, 0, 0, false);

		/** All input tokens (plain + cached), for logging and the day's token total. */
		public int totalInputTokens() {
			return inputTokens + cacheReadTokens + cacheCreationTokens;
		}

	}

	/** Why a call failed (architect decision 6 [안]; {@code NOT_CONFIGURED} added for an empty key). */
	enum Failure {

		/** No API key: nothing was sent. */
		NOT_CONFIGURED,
		/** The call took longer than its time. */
		TIMEOUT,
		/** A refused request, a connection error or a stream that broke off. */
		HTTP_ERROR,
		/** The model service is busy (HTTP 429, 529, or an {@code overloaded_error} event). */
		OVERLOADED,
		/** The answer cannot be used as it is (cut at {@code max_tokens}, a refusal, not readable). */
		BAD_OUTPUT,
		/** The caller's text consumer threw (for example the user left); the stream was closed. */
		CANCELLED

	}

	/** A failed call. The message holds the kind and status only, never the call's text or the key. */
	final class LlmException extends RuntimeException {

		private final Failure failure;

		private final transient Usage usage;

		public LlmException(Failure failure, Usage usage, String message) {
			super(failure + ": " + message);
			this.failure = failure;
			this.usage = usage;
		}

		public Failure failure() {
			return failure;
		}

		public Usage usage() {
			return usage;
		}

	}

}
