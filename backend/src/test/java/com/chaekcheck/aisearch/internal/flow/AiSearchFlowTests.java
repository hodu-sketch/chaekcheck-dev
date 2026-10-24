package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Source;
import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Card;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.CardDelta;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Done;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Text;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.TitleGuess;
import com.chaekcheck.aisearch.internal.flow.FakeLlm.Answer;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Failure;
import com.chaekcheck.aisearch.internal.llm.LlmClient.LlmCall;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Message;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Usage;
import com.chaekcheck.aisearch.internal.match.CandidateQuery;
import com.chaekcheck.aisearch.internal.quota.DailyBudget;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.usagelog.SearchConditions;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * {@link AiSearchFlow} with a fake model and fake listed books, the real keyword match and daily budget on db-test, run
 * on the test thread in the test transaction (cycle 24 handoff step 8 tests): the event order, refused numbers never
 * shown (AC-4, AC-18), the fallbacks (AC-6), no match (AC-5), the title line (AC-15), 300 characters and 8 cards,
 * follow-up questions (AC-13), a client that leaves, and what is sent to the model (R4-4).
 */
@SpringBootTest
@Transactional
class AiSearchFlowTests {

	static final String QUESTION = "잠들기 전에 조금씩 읽을 과학책";

	static final UsageRequest REQUEST = new UsageRequest(Instant.parse("2026-10-24T05:00:00Z"), "203.0.113.7",
			"signed_in", 4242L, "0123456789abcdef0123456789abcdef", true);

	@Autowired
	CandidateQuery candidates;

	@Autowired
	DailyBudget budget;

	@Autowired
	AiSearchBookTexts texts;

	@Autowired
	AiSearchProperties properties;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcClient jdbc;

	final FakeLlm llm = new FakeLlm();

	final RecordingLog usageLog = new RecordingLog();

	final List<ListedBook> listed = new ArrayList<>();

	final Sink sink = new Sink();

	@BeforeEach
	void books() {
		List<Book> stored = new ArrayList<>();
		for (int i = 1; i <= 12; i++) {
			// held by 101 (plan), 102 (plan) or 999 (not in the plan) in turn
			long library = i % 3 == 0 ? 999 : 100 + i % 3;
			listed.add(new ListedBook(i, "과학 이야기 " + i, "저자 " + i, "/covers/" + i + ".jpg", new long[] { library },
					List.of("kaist")));
			stored.add(new Book(i, "과학 이야기 " + i, "저자 " + i));
		}
		listed.add(new ListedBook(20, "토지", "박경리", null, new long[] { 101, 102 }, List.of("leedongjin")));
		stored.add(new Book(20, "토지", "박경리"));
		texts.replace(stored, List.of(new Line(20, Source.INTRO, LocalDate.of(2026, 10, 24), "한 집안의 이야기를 그린 대하소설")));
	}

	@Test
	void theAnswerStreamsHeadThenEachCheckedCardAndItsText() {
		llm.then(keywords("과학"))
			.then(Answer.pieces(List.of("{\"head\":\"잠들기 전", "에 좋아요\",\"picks\":[{\"no\":2,\"text\":\"짧", "아요\"},",
					"{\"no\":1,\"text\":\"쉬워요\"}]}"), 1800, 400));

		run(QUESTION, List.of());

		assertThat(sink.names()).containsExactly("head-delta", "head-delta", "card", "card-delta", "card-delta", "card",
				"card-delta", "done");
		List<Card> cards = sink.of(Card.class);
		assertThat(cards).extracting(Card::no).containsExactly(1, 2);
		long second = cards.getFirst().bookId();
		assertThat(sink.of(CardDelta.class)).extracting(CardDelta::bookId).containsExactly(second, second, cards.get(1).bookId());
		assertThat(cards.getFirst().myLibraryCount()).isEqualTo(1);
		assertThat(cards.getFirst().coverUrl()).startsWith("/covers/");
		Done done = sink.last(Done.class);
		assertThat(done.result()).isEqualTo("ok");
		assertThat(done.bookIds()).containsExactly(cards.get(0).bookId(), cards.get(1).bookId());
		// only books the plan libraries hold (AC-3): no book held only by 999
		assertThat(done.bookIds()).allMatch(id -> id % 3 != 0 || id == 20);
		UsageLog.AiSearchRecord record = usageLog.records.getFirst();
		assertThat(record.keywordResult()).isEqualTo(UsageLog.KeywordResult.OK);
		assertThat(record.pickResult()).isEqualTo(UsageLog.PickResult.OK);
		assertThat(record.pickedBookIds()).isEqualTo(done.bookIds());
		assertThat(record.inputTokens()).isEqualTo(900 + 1800);
		assertThat(record.outputTokens()).isEqualTo(60 + 400);
	}

	@Test
	void aNumberOutsideTheBooksOrSeenBeforeSendsNeitherCardNorText() { // AC-4, AC-18
		llm.then(keywords("과학"))
			.then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":99,\"text\":\"버릴 글\"},",
					"{\"no\":1,\"text\":\"좋은 글\"},{\"no\":1,\"text\":\"중복 글\"},{\"no\":0,\"text\":\"영 글\"}]}"), 1, 1));

		run(QUESTION, List.of());

		assertThat(sink.of(Card.class)).hasSize(1);
		assertThat(sink.of(CardDelta.class)).extracting(CardDelta::text).containsExactly("좋은 글");
		assertThat(sink.all()).noneMatch(event -> event.toString().contains("버릴") || event.toString().contains("중복")
				|| event.toString().contains("영 글"));
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void aFailedFirstCallUsesTheQuestionsWords() { // AC-6 first half
		llm.then(Answer.failAfter(List.of(), Failure.TIMEOUT, new Usage(900, 0, false)))
			.then(Answer.ok("{\"head\":\"\",\"picks\":[{\"no\":1,\"text\":\"좋아요\"}]}", 1, 1));

		run("과학 이야기를 찾아요", List.of());

		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
		assertThat(usageLog.records.getFirst().keywordResult()).isEqualTo(UsageLog.KeywordResult.TIMEOUT);
		// the broken first call is counted with its output cap (512)
		assertThat(usageLog.records.getFirst().outputTokens()).isEqualTo(properties.keywordMaxTokens() + 1);
	}

	@Test
	void aFailedSecondCallBeforeAnyCardShowsTheFirstEightWithoutText() { // AC-6 second half
		llm.then(keywords("과학"))
			.then(Answer.failAfter(List.of("{\"head\":\"잠"), Failure.OVERLOADED, new Usage(1800, 3, true)));

		run(QUESTION, List.of());

		assertThat(sink.of(Card.class)).hasSize(8);
		assertThat(sink.of(CardDelta.class)).isEmpty();
		assertThat(sink.last(Done.class).result()).isEqualTo("no_text");
		assertThat(usageLog.records.getFirst().pickResult()).isEqualTo(UsageLog.PickResult.NO_TEXT);
	}

	@Test
	void aStreamThatBreaksAfterACardEndsPartial() { // AC-18
		llm.then(keywords("과학"))
			.then(Answer.failAfter(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"text\":\"앞 글\"},{\"no\":2,\"te"),
					Failure.HTTP_ERROR, new Usage(1800, 0, false)));

		run(QUESTION, List.of());

		assertThat(sink.of(Card.class)).hasSize(2);
		assertThat(sink.last(Done.class).result()).isEqualTo("partial");
	}

	@Test
	void notABookQuestionEndsWithoutTheSecondCall() { // AC-5
		llm.then(Answer.ok("{\"notBook\":true,\"titleLike\":false,\"title\":\"\",\"refersToShown\":false,\"keywords\":[]}",
				900, 40));

		run("오늘 점심 뭐 먹지", List.of());

		assertThat(sink.names()).containsExactly("done");
		assertThat(sink.last(Done.class).result()).isEqualTo("no_match");
		assertThat(llm.calls).hasSize(1);
	}

	@Test
	void aTitleComesFirstAsATitleLineAndTheUsualAnswerFollows() { // AC-15
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":true,\"title\":\"토지\",\"refersToShown\":false,"
				+ "\"keywords\":[\"토지\",\"대하소설\"]}", 900, 60))
			.then(Answer.ok("{\"head\":\"\",\"picks\":[{\"no\":1,\"text\":\"찾으시던 책이에요\"}]}", 1, 1));

		run("토지", List.of());

		assertThat(sink.names().getFirst()).isEqualTo("title-guess");
		assertThat(sink.of(TitleGuess.class).getFirst().title()).isEqualTo("토지");
		assertThat(sink.of(Card.class).getFirst().bookId()).isEqualTo(20);

		// a title that is not in the question: the whole question goes in the line
		Sink other = new Sink();
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":true,\"title\":\"土地\",\"refersToShown\":false,"
				+ "\"keywords\":[\"토지\"]}", 1, 1)).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));
		flow().run(input("토지 박경리", List.of()), other);
		assertThat(other.of(TitleGuess.class).getFirst().title()).isEqualTo("토지 박경리");
		assertThat(other.last(Done.class).result()).isEqualTo("no_match");
	}

	@Test
	void aCardTextStopsAtThreeHundredCharactersAndAtMostEightCardsCome() {
		StringBuilder picks = new StringBuilder("{\"head\":\"\",\"picks\":[{\"no\":1,\"text\":\"" + "가".repeat(250));
		picks.append("\"},{\"no\":2,\"text\":\"").append("나".repeat(200)).append("\",\"x\":0}");
		for (int no = 3; no <= 10; no++) {
			picks.append(",{\"no\":").append(no).append(",\"text\":\"글\"}");
		}
		picks.append("]}");
		llm.then(keywords("과학", "토지")).then(Answer.pieces(List.of(picks.substring(0, 200), picks.substring(200)), 1, 1));

		run(QUESTION, List.of());

		assertThat(sink.of(Card.class)).hasSize(8);
		String first = sink.of(CardDelta.class).stream().filter(d -> d.text().startsWith("가")).map(CardDelta::text)
			.collect(Collectors.joining());
		String second = sink.of(CardDelta.class).stream().filter(d -> d.text().startsWith("나")).map(CardDelta::text)
			.collect(Collectors.joining());
		assertThat(first).hasSize(250);
		assertThat(second).hasSize(200);
		assertThat(sink.last(Done.class).bookIds()).hasSize(8);
	}

	@Test
	void aFollowUpThatPointsAtTheLastBooksStartsFromThemAndOtherwiseLeavesThemOut() { // AC-13
		AiSearchFlow.Turn earlier = new AiSearchFlow.Turn("과학책 추천해줘", List.of(20L, 1L, 777L));
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":false,\"title\":\"\",\"refersToShown\":true,"
				+ "\"keywords\":[\"소설\"]}", 1, 1)).then(Answer.ok("{\"head\":\"\",\"picks\":[{\"no\":1,\"text\":\"소설\"}]}", 1, 1));

		run("이 중에 소설만", List.of(earlier));

		LlmCall pickCall = llm.calls.get(1);
		String sent = pickCall.messages().getLast().text();
		assertThat(sent).contains("1. 제목: 토지 / 저자: 박경리 / 한 줄: 한 집안의 이야기를 그린 대하소설", "2. 제목: 과학 이야기 1");
		// the earlier turn goes in with the shown titles only; 777 is not a listed book and is dropped
		assertThat(pickCall.messages().get(1).text()).isEqualTo("보여 준 책:\n- 토지\n- 과학 이야기 1");

		// not pointing at them: the books shown before are left out
		Sink other = new Sink();
		llm.then(keywords("과학", "토지")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));
		flow().run(input("다른 과학책", List.of(earlier)), other);
		assertThat(llm.calls.get(3).messages().getLast().text()).doesNotContain("토지", "과학 이야기 1 /");
	}

	@Test
	void aClientThatLeavesStopsTheStreamAndNoDoneIsSent() {
		llm.then(keywords("과학"))
			.then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"text\":\"앞\"}", ",{\"no\":2,\"text\":\"뒤\"}]}"), 1, 1));
		sink.goneAfter = 2;

		run(QUESTION, List.of());

		assertThat(sink.names()).doesNotContain("done");
		assertThat(usageLog.records.getFirst().pickResult()).isEqualTo(UsageLog.PickResult.PARTIAL);
	}

	@Test
	void noBookHeldByThePlanEndsWithoutTheSecondCall() {
		llm.then(keywords("과학"));

		flow().run(new AiSearchFlow.Input(QUESTION, 1, List.of(), Set.of(555L), REQUEST, System.nanoTime()), sink);

		assertThat(sink.last(Done.class).result()).isEqualTo("no_match");
		assertThat(llm.calls).hasSize(1);
	}

	@Test
	void theModelGetsNoMemberIpOrLibraryName() { // spec R4-4
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run(QUESTION, List.of());

		String everything = llm.calls.stream()
			.flatMap(call -> java.util.stream.Stream.concat(java.util.stream.Stream.of(call.system()),
					call.messages().stream().map(Message::text)))
			.collect(Collectors.joining("\n"));
		assertThat(everything).contains(QUESTION).doesNotContain("203.0.113.7", "4242", "0123456789abcdef", "101", "102");
	}

	private void run(String question, List<AiSearchFlow.Turn> history) {
		flow().run(input(question, history), sink);
	}

	private AiSearchFlow.Input input(String question, List<AiSearchFlow.Turn> history) {
		return new AiSearchFlow.Input(question, history.size() + 1, history, Set.of(101L, 102L), REQUEST,
				System.nanoTime());
	}

	private AiSearchFlow flow() {
		ListedBooks books = new ListedBooks() {

			@Override
			public Snapshot current() {
				return new Snapshot(Instant.EPOCH, listed);
			}

			@Override
			public Snapshot loadFromDb() {
				return current();
			}

		};
		return new AiSearchFlow(llm, candidates, budget, books, usageLog, properties, json);
	}

	private static Answer keywords(String... words) {
		return Answer.ok("{\"notBook\":false,\"titleLike\":false,\"title\":\"\",\"refersToShown\":false,\"keywords\":["
				+ java.util.Arrays.stream(words).map(w -> "\"" + w + "\"").collect(Collectors.joining(",")) + "]}", 900, 60);
	}

	/** Keeps the events; {@link #goneAfter} makes the client leave after that many. */
	static final class Sink implements AiSearchSink {

		record Event(String name, Object data) {
		}

		final List<Event> events = new ArrayList<>();

		int goneAfter = Integer.MAX_VALUE;

		boolean completed;

		@Override
		public boolean send(String event, Object data) {
			if (events.size() >= goneAfter) {
				return false;
			}
			events.add(new Event(event, data));
			return true;
		}

		@Override
		public void complete() {
			completed = true;
		}

		List<String> names() {
			return events.stream().map(Event::name).toList();
		}

		List<Object> all() {
			return events.stream().map(Event::data).toList();
		}

		<T> List<T> of(Class<T> type) {
			return events.stream().map(Event::data).filter(type::isInstance).map(type::cast).toList();
		}

		<T> T last(Class<T> type) {
			return of(type).getLast();
		}

	}

	static final class RecordingLog implements UsageLog {

		final List<AiSearchRecord> records = new ArrayList<>();

		@Override
		public UsageRequest capture(jakarta.servlet.http.HttpServletRequest request) {
			return UsageRequest.NOT_RECORDED;
		}

		@Override
		public void recordSearch(UsageRequest request, SearchKind kind, SearchConditions conditions, Integer resultCount) {
		}

		@Override
		public void recordBookView(UsageRequest request, long bookId, List<Long> residenceRegionIds,
				List<Long> workRegionIds, List<Long> libraryIds) {
		}

		@Override
		public void recordMore(UsageRequest request, MoreKind kind, int seq, Long libraryId, SearchConditions conditions) {
		}

		@Override
		public void recordLibraryResponse(long libraryId, ResponseSource source, ResponseResult result, long elapsedMs) {
		}

		@Override
		public void recordAiSearch(UsageRequest request, AiSearchRecord record) {
			records.add(record);
		}

	}

}
