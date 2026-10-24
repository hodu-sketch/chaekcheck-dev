package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * {@link PickStreamParser} (cycle 24 handoff step 8-1): the events come in order, the number before its text, and the
 * same events come however the answer is cut into pieces (escapes and surrogate pairs split included).
 */
class PickStreamParserTests {

	static final String ANSWER = """
			{ "head" : "잠들기 전에 \\"조금씩\\" 읽기 좋은 책이에요 😀\\n",
			  "picks": [
			    {"no": 3, "text": "짧은 장으로 나뉘어 \\u3010부담\\u3011 없어요 \\ud83d\\ude00"},
			    {"no":12,"text":"두 번째\\\\책"} ,
			    { "no" : 1 , "text" : "" }
			  ] }""";

	static final List<String> EVENTS = List.of("head:잠들기 전에 \"조금씩\" 읽기 좋은 책이에요 😀\n", "no:3",
			"text:짧은 장으로 나뉘어 【부담】 없어요 😀", "end", "no:12", "text:두 번째\\책", "end", "no:1", "end");

	@Test
	void readsTheAnswerInOrderInOnePiece() {
		Recorder recorder = new Recorder();
		PickStreamParser parser = new PickStreamParser(recorder);

		parser.feed(ANSWER);

		assertThat(recorder.joined()).containsExactlyElementsOf(EVENTS);
		assertThat(parser.complete()).isTrue();
	}

	@Test
	void theSameEventsComeOneCharacterAtATime() {
		Recorder recorder = new Recorder();
		PickStreamParser parser = new PickStreamParser(recorder);

		for (int i = 0; i < ANSWER.length(); i++) {
			parser.feed(ANSWER.substring(i, i + 1));
		}

		assertThat(recorder.joined()).containsExactlyElementsOf(EVENTS);
		assertThat(parser.complete()).isTrue();
		// a surrogate pair split over two pieces is never handed on half
		assertThat(recorder.raw).noneMatch(text -> !text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1)));
	}

	@Test
	void theSameEventsComeAtEverySplitAndRandomCuts() {
		for (int cut = 1; cut < ANSWER.length(); cut++) {
			Recorder recorder = new Recorder();
			PickStreamParser parser = new PickStreamParser(recorder);
			parser.feed(ANSWER.substring(0, cut));
			parser.feed(ANSWER.substring(cut));
			assertThat(recorder.joined()).as("cut at %d", cut).containsExactlyElementsOf(EVENTS);
		}
		Random random = new Random(24);
		for (int run = 0; run < 200; run++) {
			Recorder recorder = new Recorder();
			PickStreamParser parser = new PickStreamParser(recorder);
			int at = 0;
			while (at < ANSWER.length()) {
				int next = Math.min(ANSWER.length(), at + 1 + random.nextInt(9));
				parser.feed(ANSWER.substring(at, next));
				at = next;
			}
			assertThat(recorder.joined()).containsExactlyElementsOf(EVENTS);
		}
	}

	@Test
	void theNumberIsToldOnlyWhenItHasEnded() {
		Recorder recorder = new Recorder();
		PickStreamParser parser = new PickStreamParser(recorder);

		parser.feed("{\"head\":\"\",\"picks\":[{\"no\":1");
		assertThat(recorder.raw).isEmpty();
		parser.feed("2,\"te");
		assertThat(recorder.raw).containsExactly("no:12");
		assertThat(parser.complete()).isFalse();
	}

	@Test
	void anEmptyListAndOtherFieldsAreRead() {
		Recorder recorder = new Recorder();
		PickStreamParser parser = new PickStreamParser(recorder);

		parser.feed("{\"head\":\"맞는 책이 적어요\",\"extra\":{\"text\":\"무시\",\"n\":[1,true,null,-2.5e3]},\"picks\":[]}");

		assertThat(recorder.joined()).containsExactly("head:맞는 책이 적어요");
		assertThat(parser.complete()).isTrue();
	}

	@Test
	void notJsonIsRefused() {
		assertThatThrownBy(() -> new PickStreamParser(new Recorder()).feed("{\"head\" \"x\"}"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new PickStreamParser(new Recorder()).feed("{\"head\":\"x\"} more"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new PickStreamParser(new Recorder()).feed("{\"head\":\"\\q\"}"))
			.isInstanceOf(IllegalStateException.class);
	}

	/** Records the events; text pieces of one string are joined in {@link #joined}. */
	static final class Recorder implements PickStreamParser.Listener {

		final List<String> raw = new ArrayList<>();

		@Override
		public void headText(String text) {
			raw.add("head:" + text);
		}

		@Override
		public void pickNo(long no) {
			raw.add("no:" + no);
		}

		@Override
		public void pickText(String text) {
			raw.add("text:" + text);
		}

		@Override
		public void pickEnd() {
			raw.add("end");
		}

		List<String> joined() {
			List<String> joined = new ArrayList<>();
			for (String event : raw) {
				String kind = event.substring(0, event.indexOf(':') + 1);
				if (!joined.isEmpty() && !kind.isEmpty() && (kind.equals("head:") || kind.equals("text:"))
						&& joined.getLast().startsWith(kind)) {
					joined.set(joined.size() - 1, joined.getLast() + event.substring(kind.length()));
				}
				else {
					joined.add(event);
				}
			}
			return joined;
		}

	}

}
