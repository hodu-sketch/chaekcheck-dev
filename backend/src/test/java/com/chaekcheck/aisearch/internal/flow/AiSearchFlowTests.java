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
import com.chaekcheck.aisearch.internal.match.SearchBooks;
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

	/** The search books: the listed ones and, like the most held books, ones on no list. */
	final List<Book> stored = new ArrayList<>();

	final List<Line> lines = new ArrayList<>();

	final Sink sink = new Sink();

	/** The neighbours the fake {@code foryou} knows, by book. */
	final java.util.Map<Long, List<Long>> neighbourMap = new java.util.HashMap<>();

	final com.chaekcheck.foryou.BookNeighbours neighbours = ids -> {
		java.util.Map<Long, List<Long>> found = new java.util.HashMap<>();
		ids.forEach(id -> {
			if (neighbourMap.containsKey(id)) {
				found.put(id, neighbourMap.get(id));
			}
		});
		return found;
	};

	/** The tab covers the fake {@code foryou} knows, by book. */
	final java.util.Map<Long, String> tabCovers = new java.util.HashMap<>();

	final com.chaekcheck.foryou.BookCovers covers = ids -> {
		java.util.Map<Long, String> found = new java.util.HashMap<>();
		ids.stream().filter(tabCovers::containsKey).forEach(id -> found.put(id, tabCovers.get(id)));
		return found;
	};

	@BeforeEach
	void books() {
		for (int i = 1; i <= 12; i++) {
			// held by 101 (plan), 102 (plan) or 999 (not in the plan) in turn
			long library = i % 3 == 0 ? 999 : 100 + i % 3;
			listed.add(new ListedBook(i, "과학 이야기 " + i, "저자 " + i, "/covers/" + i + ".jpg", new long[] { library },
					List.of("kaist"), "과학 이야기 " + i + "의 AI 요약"));
			stored.add(new Book(i, "과학 이야기 " + i, "저자 " + i, new long[] { library }));
		}
		listed.add(new ListedBook(20, "토지", "박경리", null, new long[] { 101, 102 }, List.of("leedongjin")));
		stored.add(new Book(20, "토지", "박경리", new long[] { 101, 102 }));
		lines.add(new Line(20, Source.INTRO, LocalDate.of(2026, 10, 24), "한 집안의 이야기를 그린 대하소설"));
		texts.replace(stored, lines);
	}

	/** Adds a listed book to the lists and the search books. */
	private void addListed(ListedBook book) {
		listed.add(book);
		stored.add(new Book(book.bookId(), book.title(), book.author(), book.libraryIds()));
		texts.replace(stored, lines);
	}

	@Test
	void aBookOnNoListIsAnsweredWithoutCoverAndSummary() { // user 2026-10-25: the most held books
		stored.add(new Book(500, "우주 과학 교양", "칼 세이건", new long[] { 101, 102, 103 }));
		lines.add(new Line(500, Source.INTRO, LocalDate.of(2026, 10, 25), "별과 은하를 쉽게 풀어 쓴 천문학 교양서"));
		texts.replace(stored, lines);
		llm.then(keywords("천문학"))
			.then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"별 이야기\"}]}"), 1, 1));

		run(QUESTION, List.of());

		Card card = sink.of(Card.class).getFirst();
		assertThat(card.bookId()).isEqualTo(500);
		assertThat(card.title()).isEqualTo("우주 과학 교양");
		assertThat(card.author()).isEqualTo("칼 세이건");
		assertThat(card.myLibraryCount()).isEqualTo(2);
		assertThat(card.coverUrl()).isNull();
		assertThat(card.aiSummary()).isNull();
		// the second call saw the stored line
		assertThat(llm.calls.get(1).messages().getLast().text()).contains("별과 은하를 쉽게 풀어 쓴 천문학 교양서");
	}

	@Test
	void aCardWithoutAListCoverTakesTheTabCover() { // user 2026-10-25, tab covers from foryou
		stored.add(new Book(500, "우주 과학 교양", "칼 세이건", new long[] { 101, 102 }));
		texts.replace(stored, lines);
		tabCovers.put(500L, "/covers/" + "a".repeat(40) + ".jpg");
		tabCovers.put(20L, "/covers/" + "b".repeat(40) + ".jpg");
		tabCovers.put(1L, "/covers/" + "c".repeat(40) + ".jpg");
		llm.then(keywords("과학", "토지", "우주"))
			.then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"},"
					+ "{\"no\":2,\"fit\":3,\"text\":\"나\"},{\"no\":3,\"fit\":3,\"text\":\"다\"}]}"), 1, 1));

		run(QUESTION, List.of());

		java.util.Map<Long, String> shown = new java.util.HashMap<>();
		sink.of(Card.class).forEach(card -> shown.put(card.bookId(), card.coverUrl()));
		assertThat(shown).hasSize(3);
		shown.forEach((bookId, cover) -> {
			switch (bookId.intValue()) {
				case 500, 20 -> assertThat(cover).isEqualTo(tabCovers.get(bookId)); // no list cover
				default -> assertThat(cover).isEqualTo("/covers/" + bookId + ".jpg"); // the list cover stays
			}
		});
	}

	@Test
	void theAnswerStreamsHeadThenEachCheckedCardAndItsText() {
		llm.then(keywords("과학"))
			.then(Answer.pieces(List.of("{\"head\":\"잠들기 전", "에 좋아요\",\"picks\":[{\"no\":2,\"fit\":3,\"text\":\"짧", "아요\"},",
					"{\"no\":1,\"fit\":3,\"text\":\"쉬워요\"}]}"), 1800, 400));

		run(QUESTION, List.of());

		assertThat(sink.names()).containsExactly("head-delta", "head-delta", "card", "card-delta", "card-delta", "card",
				"card-delta", "done");
		List<Card> cards = sink.of(Card.class);
		assertThat(cards).extracting(Card::no).containsExactly(1, 2);
		long second = cards.getFirst().bookId();
		assertThat(sink.of(CardDelta.class)).extracting(CardDelta::bookId).containsExactly(second, second, cards.get(1).bookId());
		assertThat(cards.getFirst().myLibraryCount()).isEqualTo(1);
		assertThat(cards.getFirst().coverUrl()).startsWith("/covers/");
		// the lists' AI summary rides on the card for the book window (user 2026-10-24)
		assertThat(cards.getFirst().aiSummary()).isEqualTo(cards.getFirst().title() + "의 AI 요약");
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
	void aPickWithALowFitSendsNeitherCardNorText() { // cycle 24 1단계
		llm.then(keywords("과학"))
			.then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":1,\"text\":\"주제가 다른 책\"},",
					"{\"no\":2,\"fit\":2,\"text\":\"맞는 책\"}]}"), 1, 1));

		run(QUESTION, List.of());

		assertThat(sink.of(Card.class)).hasSize(1);
		assertThat(sink.of(CardDelta.class)).extracting(CardDelta::text).containsExactly("맞는 책");
		assertThat(sink.last(Done.class).bookIds()).hasSize(1);
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void booksWithTheCoreWordsComeFirst() { // cycle 24 1단계
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":false,\"title\":\"\",\"refersToShown\":false,"
				+ "\"keywords\":[\"과학\",\"이야기\"],\"core\":[\"대하소설\"]}", 1, 1))
			.then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run("대하소설 이야기", List.of());

		// 토지 holds 대하소설 (its line) and comes first, before the twelve 과학 이야기 books
		assertThat(llm.calls.get(1).messages().getLast().text()).contains("1. 제목: 토지");
	}

	@Test
	void aNumberOutsideTheBooksOrSeenBeforeSendsNeitherCardNorText() { // AC-4, AC-18
		llm.then(keywords("과학"))
			.then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":99,\"fit\":3,\"text\":\"버릴 글\"},",
					"{\"no\":1,\"fit\":3,\"text\":\"좋은 글\"},{\"no\":1,\"fit\":3,\"text\":\"중복 글\"},{\"no\":0,\"fit\":3,\"text\":\"영 글\"}]}"), 1, 1));

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
			.then(Answer.ok("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"좋아요\"}]}", 1, 1));

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
			.then(Answer.failAfter(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"앞 글\"},{\"no\":2,\"fit\":3,\"te"),
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
			.then(Answer.ok("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"찾으시던 책이에요\"}]}", 1, 1));

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
		StringBuilder picks = new StringBuilder("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"" + "가".repeat(250));
		picks.append("\"},{\"no\":2,\"fit\":3,\"text\":\"").append("나".repeat(200)).append("\",\"x\":0}");
		for (int no = 3; no <= 10; no++) {
			picks.append(",{\"no\":").append(no).append(",\"fit\":3,\"text\":\"글\"}");
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
				+ "\"keywords\":[\"소설\"]}", 1, 1)).then(Answer.ok("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"소설\"}]}", 1, 1));

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
	void aFollowUpAboutTheBooksShownAlsoGetsTheirNeighbours() { // user 2026-10-25, neighbours from foryou
		// 20's neighbours: 3 (held by 999 only, not in the plan), 4, 1 (shown before), 5; 1's: 7
		neighbourMap.put(20L, List.of(3L, 4L, 1L, 5L));
		neighbourMap.put(1L, List.of(7L));
		AiSearchFlow.Turn earlier = new AiSearchFlow.Turn("과학책 추천해줘", List.of(20L, 1L));
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":false,\"title\":\"\",\"refersToShown\":true,"
				+ "\"keywords\":[]}", 1, 1)).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run("두 번째 책 비슷한 것", List.of(earlier));

		String sent = llm.calls.get(1).messages().getLast().text();
		// the shown books, then the nearest of each (3 is not held, so 7), then the next (4), then 5; the shown 1 is not
		// taken again
		assertThat(sent).contains("1. 제목: 토지", "2. 제목: 과학 이야기 1 /", "3. 제목: 과학 이야기 7 /", "4. 제목: 과학 이야기 4 /",
				"5. 제목: 과학 이야기 5 /")
			.doesNotContain("과학 이야기 3 /");
	}

	@Test
	void aClientThatLeavesStopsTheStreamAndNoDoneIsSent() {
		llm.then(keywords("과학"))
			.then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"앞\"}", ",{\"no\":2,\"fit\":3,\"text\":\"뒤\"}]}"), 1, 1));
		sink.goneAfter = 2;

		run(QUESTION, List.of());

		assertThat(sink.names()).doesNotContain("done");
		assertThat(usageLog.records.getFirst().pickResult()).isEqualTo(UsageLog.PickResult.PARTIAL);
	}

	@Test
	void aFailingUsageLogStillEndsTheStream() {
		llm.then(Answer.ok("{\"notBook\":true,\"titleLike\":false,\"title\":\"\",\"refersToShown\":false,\"keywords\":[]}",
				1, 1));
		RecordingLog failing = new RecordingLog() {

			@Override
			public void recordAiSearch(UsageRequest request, AiSearchRecord record) {
				throw new IllegalStateException("full");
			}

		};
		AiSearchFlow broken = new AiSearchFlow(llm, candidates, budget, listedBooks(), new SearchBooks(jdbc), neighbours, covers, failing, properties,
				json);

		broken.run(input(QUESTION, List.of()), sink);

		assertThat(sink.completed).isTrue();
		assertThat(sink.names()).containsExactly("done");
	}

	@Test
	void aTitleGuessNeverHoldsHalfACharacter() {
		addListed(new ListedBook(30, "웃는 책", "저자 30", null, new long[] { 101 }, List.of("kaist")));
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":true,\"title\":\"웃는 책 \\ud83d\",\"refersToShown\":false,"
				+ "\"keywords\":[]}", 1, 1));

		run("웃는 책 😀", List.of());

		assertThat(sink.of(TitleGuess.class).getFirst().title()).isEqualTo("웃는 책 \uFFFD");
	}

	@Test
	void aTopicSentenceTakenForATitleSendsNoTitleLine() { // issue 2-1 (나)
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":true,\"title\":\"세네카와 쇼펜하우어의 철학\","
				+ "\"refersToShown\":false,\"keywords\":[\"세네카\",\"쇼펜하우어\",\"철학\"]}", 1, 1));

		run("세네카와 쇼펜하우어의 철학", List.of());

		assertThat(sink.of(TitleGuess.class)).isEmpty();
		assertThat(sink.last(Done.class).result()).isEqualTo("no_match");
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
		return new AiSearchFlow(llm, candidates, budget, listedBooks(), new SearchBooks(jdbc), neighbours, covers, usageLog, properties, json);
	}

	private ListedBooks listedBooks() {
		return new ListedBooks() {

			@Override
			public Snapshot current() {
				return new Snapshot(Instant.EPOCH, listed);
			}

			@Override
			public Snapshot loadFromDb() {
				return current();
			}

		};
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

	static class RecordingLog implements UsageLog {

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
