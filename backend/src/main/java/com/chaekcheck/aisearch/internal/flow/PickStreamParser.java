package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Reads the second call's JSON while it streams (cycle 24 handoff step 8-1, ADR-0153): the answer follows the schema
 * {@code {"head": string, "picks": [{"no": integer, "fit": integer, "text": string}, …], "tail": string}} with every field required, so the fields come
 * in that order (structured outputs). A small push-down reader, fed the text pieces in order: tells the head's text as
 * it comes, a pick's number once the number has ended (so it can be checked before any of its text is shown), the
 * pick's text as it comes, the end of each pick, and the closing summary's text as it comes. Escapes ({@code \"}, {@code \\uXXXX}, surrogate pairs) may be split
 * over pieces; a surrogate without its pair becomes U+FFFD. Anything that is not JSON throws {@link IllegalStateException}. Not thread-safe; one per answer.
 */
public final class PickStreamParser {

	/** What the answer says, in order. */
	public interface Listener {

		void headText(String text);

		void pickNo(long no);

		/** How well the pick fits the question, 0 to 3, after its number and before its text (cycle 24 1단계). */
		default void pickFit(long fit) {
		}

		void pickText(String text);

		void pickEnd();

		/** The ask-back question, whole, when the answer has an {@code ask} field (it comes first). */
		default void askQuestion(String question) {
		}

		/** One ask-back choice, whole. */
		default void askChoice(String choice) {
		}

		/** The closing summary by situation, as it comes, after the picks (user 2026-10-29 「상황별 정리」). */
		default void tailText(String text) {
		}

	}

	private enum Mode {

		VALUE, VALUE_OR_END, KEY_OR_END, KEY, COLON, AFTER_VALUE, STRING, ESCAPE, UNICODE, NUMBER, LITERAL, DONE

	}

	private enum Target {

		NONE, HEAD, TEXT, TAIL, ASK_QUESTION, ASK_CHOICE

	}

	private static final class Frame {

		final boolean object;

		String key;

		Frame(boolean object) {
			this.object = object;
		}

	}

	/** Stands for a surrogate without its pair, so only whole characters are handed on. */
	static final char REPLACEMENT = '\uFFFD';

	private final Listener listener;

	private final Deque<Frame> stack = new ArrayDeque<>();

	private Mode mode = Mode.VALUE;

	/** Where the open string goes, and whether it is a key. */
	private Target target = Target.NONE;

	private boolean readingKey;

	private final StringBuilder key = new StringBuilder();

	private final StringBuilder out = new StringBuilder();

	private final StringBuilder number = new StringBuilder();

	/** The ask-back string being read; it is handed on whole. */
	private final StringBuilder ask = new StringBuilder();

	private final StringBuilder hex = new StringBuilder();

	private char pendingHigh;

	private int literalLeft;

	public PickStreamParser(Listener listener) {
		this.listener = listener;
	}

	/** Reads one more piece of the answer. */
	public void feed(String piece) {
		for (int i = 0; i < piece.length(); i++) {
			step(piece.charAt(i));
		}
		flush();
	}

	/** Whether the whole answer has been read (the outer object closed). */
	public boolean complete() {
		return mode == Mode.DONE;
	}

	private void step(char c) {
		switch (mode) {
			case STRING -> {
				if (c == '"') {
					endString();
				}
				else if (c == '\\') {
					mode = Mode.ESCAPE;
				}
				else if (c < 0x20) {
					throw bad("a control character in a string");
				}
				else {
					append(c);
				}
			}
			case ESCAPE -> {
				mode = Mode.STRING;
				switch (c) {
					case '"', '\\', '/' -> append(c);
					case 'n' -> append('\n');
					case 't' -> append('\t');
					case 'r' -> append('\r');
					case 'b' -> append('\b');
					case 'f' -> append('\f');
					case 'u' -> {
						hex.setLength(0);
						mode = Mode.UNICODE;
					}
					default -> throw bad("an unknown escape");
				}
			}
			case UNICODE -> {
				if (Character.digit(c, 16) < 0) {
					throw bad("a bad \\u escape");
				}
				hex.append(c);
				if (hex.length() == 4) {
					mode = Mode.STRING;
					append((char) Integer.parseInt(hex.toString(), 16));
				}
			}
			case NUMBER -> {
				if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
					number.append(c);
				}
				else {
					endNumber();
					step(c);
				}
			}
			case LITERAL -> {
				if (--literalLeft == 0) {
					afterValue();
				}
			}
			default -> structure(c);
		}
	}

	private void structure(char c) {
		if (Character.isWhitespace(c)) {
			return;
		}
		switch (mode) {
			case VALUE, VALUE_OR_END -> {
				if (mode == Mode.VALUE_OR_END && c == ']') {
					endContainer(false);
					return;
				}
				startValue(c);
			}
			case KEY_OR_END -> {
				if (c == '}') {
					endContainer(true);
				}
				else if (c == '"') {
					readingKey = true;
					key.setLength(0);
					mode = Mode.STRING;
				}
				else {
					throw bad("a key was expected");
				}
			}
			case COLON -> {
				if (c != ':') {
					throw bad("a colon was expected");
				}
				mode = Mode.VALUE;
			}
			case AFTER_VALUE -> {
				Frame frame = stack.peek();
				if (frame == null) {
					throw bad("text after the answer");
				}
				if (c == ',') {
					mode = frame.object ? Mode.KEY_OR_END : Mode.VALUE;
				}
				else if (c == '}' && frame.object) {
					endContainer(true);
				}
				else if (c == ']' && !frame.object) {
					endContainer(false);
				}
				else {
					throw bad("a comma or an end was expected");
				}
			}
			case DONE -> throw bad("text after the answer");
			default -> throw new IllegalStateException(mode.name());
		}
	}

	private void startValue(char c) {
		if (c == '{') {
			stack.push(new Frame(true));
			mode = Mode.KEY_OR_END;
		}
		else if (c == '[') {
			stack.push(new Frame(false));
			mode = Mode.VALUE_OR_END;
		}
		else if (c == '"') {
			readingKey = false;
			target = targetOfValue();
			mode = Mode.STRING;
		}
		else if (c == '-' || Character.isDigit(c)) {
			number.setLength(0);
			number.append(c);
			mode = Mode.NUMBER;
		}
		else if (c == 't' || c == 'n') {
			literalLeft = 3;
			mode = Mode.LITERAL;
		}
		else if (c == 'f') {
			literalLeft = 4;
			mode = Mode.LITERAL;
		}
		else {
			throw bad("a value was expected");
		}
	}

	private Target targetOfValue() {
		Frame frame = stack.peek();
		if (inAskChoices()) {
			return Target.ASK_CHOICE;
		}
		if (frame == null || !frame.object) {
			return Target.NONE;
		}
		if (stack.size() == 2 && "question".equals(frame.key) && "ask".equals(stack.peekLast().key)) {
			return Target.ASK_QUESTION;
		}
		if (stack.size() == 1 && "head".equals(frame.key)) {
			return Target.HEAD;
		}
		if (stack.size() == 1 && "tail".equals(frame.key)) {
			return Target.TAIL;
		}
		if (inPick() && "text".equals(frame.key)) {
			return Target.TEXT;
		}
		return Target.NONE;
	}

	/** The top frame is the {@code choices} array of the outer object's {@code ask}. */
	private boolean inAskChoices() {
		if (stack.size() != 3) {
			return false;
		}
		Frame[] frames = stack.toArray(new Frame[0]);
		return !frames[0].object && frames[1].object && "choices".equals(frames[1].key) && frames[2].object
				&& "ask".equals(frames[2].key);
	}

	/** The top frame is an object in the {@code picks} array of the outer object. */
	private boolean inPick() {
		if (stack.size() != 3) {
			return false;
		}
		Frame[] frames = stack.toArray(new Frame[0]);
		return frames[0].object && !frames[1].object && frames[2].object && "picks".equals(frames[2].key);
	}

	private void append(char c) {
		if (readingKey) {
			key.append(c);
			return;
		}
		if (target == Target.NONE) {
			return;
		}
		if (pendingHigh != 0) {
			if (Character.isLowSurrogate(c)) {
				out.append(pendingHigh).append(c);
				pendingHigh = 0;
				return;
			}
			// a high surrogate without its pair is never handed on
			out.append(REPLACEMENT);
			pendingHigh = 0;
		}
		if (Character.isHighSurrogate(c)) {
			pendingHigh = c;
		}
		else {
			out.append(Character.isLowSurrogate(c) ? REPLACEMENT : c);
		}
	}

	private void endString() {
		if (readingKey) {
			stack.peek().key = key.toString();
			readingKey = false;
			mode = Mode.COLON;
			return;
		}
		if (pendingHigh != 0) {
			out.append(REPLACEMENT);
			pendingHigh = 0;
		}
		flush();
		if (target == Target.ASK_QUESTION) {
			listener.askQuestion(ask.toString());
		}
		else if (target == Target.ASK_CHOICE) {
			listener.askChoice(ask.toString());
		}
		ask.setLength(0);
		target = Target.NONE;
		afterValue();
	}

	private void endNumber() {
		Frame frame = stack.peek();
		if (frame != null && frame.object && inPick() && "no".equals(frame.key)) {
			long no;
			try {
				no = Long.parseLong(number.toString());
			}
			catch (NumberFormatException e) {
				throw bad("a pick number that is not a whole number");
			}
			listener.pickNo(no);
		}
		else if (frame != null && frame.object && inPick() && "fit".equals(frame.key)) {
			long fit;
			try {
				fit = Long.parseLong(number.toString());
			}
			catch (NumberFormatException e) {
				throw bad("a fit that is not a whole number");
			}
			listener.pickFit(fit);
		}
		afterValue();
	}

	private void endContainer(boolean object) {
		boolean wasPick = object && inPick();
		stack.pop();
		if (wasPick) {
			listener.pickEnd();
		}
		afterValue();
	}

	private void afterValue() {
		mode = stack.isEmpty() ? Mode.DONE : Mode.AFTER_VALUE;
	}

	/** Hands on the text read in this piece (a high surrogate waits for its pair). */
	private void flush() {
		if (out.isEmpty()) {
			return;
		}
		String text = out.toString();
		out.setLength(0);
		if (target == Target.HEAD) {
			listener.headText(text);
		}
		else if (target == Target.TEXT) {
			listener.pickText(text);
		}
		else if (target == Target.TAIL) {
			listener.tailText(text);
		}
		else {
			ask.append(text);
		}
	}

	private static IllegalStateException bad(String what) {
		return new IllegalStateException("the answer is not the expected JSON: " + what);
	}

}
