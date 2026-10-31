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

import com.chaekcheck.aisearch.TestSearchBooks;
import com.chaekcheck.aisearch.TestSearchBooks.Book;
import com.chaekcheck.catalog.BookTextsTransfer.Line;
import com.chaekcheck.catalog.BookTextsTransfer.Source;
import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.firstcall.MlFirstCall;
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
	com.chaekcheck.catalog.AiSearchIndex searchIndex;

	@Autowired
	DailyBudget budget;

	@Autowired
	TestSearchBooks texts;

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

	/** The tab blurbs the fake {@code foryou} knows, by book. */
	final java.util.Map<Long, String> tabBlurbs = new java.util.HashMap<>();

	final java.util.function.Function<java.util.Collection<Long>, java.util.Map<Long, String>> blurbs = ids -> {
		java.util.Map<Long, String> found = new java.util.HashMap<>();
		ids.stream().filter(tabBlurbs::containsKey).forEach(id -> found.put(id, tabBlurbs.get(id)));
		return found;
	};

	final java.util.function.Function<java.util.Collection<Long>, java.util.Map<Long, String>> covers = ids -> {
		java.util.Map<Long, String> found = new java.util.HashMap<>();
		ids.stream().filter(tabCovers::containsKey).forEach(id -> found.put(id, tabCovers.get(id)));
		return found;
	};

	/** The question vectors the flow gets; off unless a test sets them. */
	com.chaekcheck.aisearch.internal.vector.QuestionVectors questionVectors =
			com.chaekcheck.aisearch.internal.vector.QuestionVectors.OFF;

	/** The publish years the fake catalog knows, by book. */
	final java.util.Map<Long, Integer> publishYears = new java.util.HashMap<>();

	/** A catalog that knows only the publish years given. */
	static com.chaekcheck.catalog.BookCards years(java.util.Map<Long, Integer> byBook) {
		return new com.chaekcheck.catalog.BookCards() {

			@Override
			public List<BookCard> cards(java.util.Collection<Long> bookIds) {
				return bookIds.stream()
					.filter(byBook::containsKey)
					.map(id -> new BookCard(id, "", null, null, byBook.get(id), null, new long[0]))
					.toList();
			}

			@Override
			public java.util.Map<HoldingKey, Long> byHoldingKeys(java.util.Collection<HoldingKey> keys) {
				return java.util.Map.of();
			}

			@Override
			public java.util.Map<Long, Long> workKeys(java.util.Collection<Long> bookIds) {
				return java.util.Map.of();
			}

		};
	}

	@Test
	void theSecondCallGetsThisYearAndEachBooksPublishYear() { // user 2026-10-26 "출간시기도"
		publishYears.put(20L, 2019);
		llm.then(keywords("토지")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run("최근에 나온 대하소설", List.of());

		String message = llm.calls.get(1).messages().getLast().text();
		assertThat(message).startsWith("올해: " + java.time.Year.now(java.time.ZoneId.of("Asia/Seoul")).getValue() + "년\n질문: ");
		assertThat(message).contains("제목: 토지 / 저자: 박경리 / 출간: 2019년 / 한 줄: ");
		assertThat(Prompts.PICK_SYSTEM).contains("확인하기 어려워요").contains("최근에 나온 책을 먼저");
	}

	@Test
	void aNamedFirstYearLeavesTheOlderBooksOut() { // user 2026-10-31 「최소한 작년에 나온 JAVA 도서」 showed a 2024 book
		int lastYear = java.time.Year.now(java.time.ZoneId.of("Asia/Seoul")).getValue() - 1;
		jdbc.sql("UPDATE catalog.book SET published_on = make_date(:year, 3, 1) WHERE id IN (1, 2)")
			.param("year", lastYear)
			.update();
		jdbc.sql("UPDATE catalog.book SET published_on = make_date(:year, 3, 1) WHERE id IN (4, 5, 7, 8, 10, 11)")
			.param("year", lastYear - 1)
			.update();
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		try {
			run("최소한 작년에 나온 과학 책", List.of());
		}
		finally {
			jdbc.sql("UPDATE catalog.book SET published_on = NULL WHERE id <= 12").update();
		}

		String message = llm.calls.get(1).messages().getLast().text();
		assertThat(message).contains("제목: 과학 이야기 1 /").contains("제목: 과학 이야기 2 /")
			.doesNotContain("제목: 과학 이야기 4 /")
			.doesNotContain("제목: 과학 이야기 5 /");
		String call = llm.calls.get(1).system() + llm.calls.get(1).messages();
		assertThat(call).contains(lastYear + "년 이후에 나온 책만 원한다");
	}

	@Test
	void aNamedFirstYearWithNoBookThatNewFindsNothing() { // user 2026-10-31
		// the test books have no publish date: none can be shown to meet the year
		llm.then(keywords("과학"));

		run("최소한 작년에 나온 과학 책", List.of());

		assertThat(llm.calls).hasSize(1);
		assertThat(sink.last(Done.class).result()).isEqualTo("no_match");
	}

	/** The ML first call the flow gets; off unless a test sets it. */
	MlFirstCall mlFirstCall = MlFirstCall.off();

	/** The second step: the single call unless a test sets the agent. */
	com.chaekcheck.aisearch.internal.config.PickProperties pick = new com.chaekcheck.aisearch.internal.config.PickProperties(false, false);

	/** The settings the flow gets: the application's, or with the ML first call on. */
	AiSearchProperties flowProperties;

	/** The texts the ML first call turned into vectors, in order. */
	final List<String> encoded = new java.util.concurrent.CopyOnWriteArrayList<>();

	/**
	 * The ML first call on (user 2026-10-26) with the tiny model: the texts become the given vectors (a text not given
	 * fails), and the nearest books of any vector are {@code nearest}.
	 */
	void mlOn(java.util.Map<String, float[]> vectorOf, List<Long> nearest) {
		mlFirstCall = com.chaekcheck.aisearch.internal.firstcall.TinyFirstCall.create();
		flowProperties = new AiSearchProperties(properties.enabled(), properties.zone(), properties.keywordTimeout(),
				properties.pickTimeout(), properties.keywordMaxTokens(), properties.pickMaxTokens(),
				properties.maxConcurrent(), properties.rate(), properties.budget(), properties.price(),
				properties.limits(), properties.vector(),
				new AiSearchProperties.FirstCall(AiSearchProperties.FirstCallMode.ML, 0.004, 512, null, null));
		questionVectors = new com.chaekcheck.aisearch.internal.vector.QuestionVectors() {

			@Override
			public boolean on() {
				return true;
			}

			@Override
			public java.util.concurrent.CompletableFuture<List<Long>> nearest(String question, int limit) {
				return java.util.concurrent.CompletableFuture.completedFuture(nearest);
			}

			@Override
			public java.util.concurrent.CompletableFuture<List<float[]>> vectors(List<String> texts) {
				encoded.addAll(texts);
				if (!vectorOf.keySet().containsAll(texts)) {
					return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("no vector"));
				}
				return java.util.concurrent.CompletableFuture.completedFuture(texts.stream().map(vectorOf::get).toList());
			}

			@Override
			public List<Long> nearest(float[] vector, int limit) {
				return nearest;
			}

		};
	}

	@Test
	void withTheMlFirstCallABookQuestionMakesOnlyTheSecondCall() { // user 2026-10-26
		mlOn(java.util.Map.of("과학 책 추천해줘", com.chaekcheck.aisearch.internal.firstcall.TinyFirstCall.BOOK),
				List.of(2L, 5L, 7L));
		llm.then(Answer.ok("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"맞는 책\"}]}", 1, 1));

		run("과학 책 추천해줘", List.of());

		assertThat(llm.calls).hasSize(1);
		assertThat(llm.calls.getFirst().system()).isEqualTo(Prompts.PICK_SYSTEM);
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
		assertThat(usageLog.records.getFirst().keywordResult()).isEqualTo(UsageLog.KeywordResult.OK);
	}

	@Test
	void withTheMlFirstCallAQuestionAbout책첵IsAnsweredFromTheSiteText() { // user 2026-10-26
		mlOn(java.util.Map.of("책첵은 무료야?", com.chaekcheck.aisearch.internal.firstcall.TinyFirstCall.SITE), List.of());
		llm.then(Answer.ok("{\"answer\":\"네, 책첵은 무료예요.\"}", 300, 20));

		run("책첵은 무료야?", List.of());

		assertThat(llm.calls).hasSize(1);
		assertThat(llm.calls.getFirst().system()).isEqualTo(Prompts.SITE_SYSTEM).contains("무료").doesNotContain("DB-IP");
		assertThat(sink.names()).containsExactly("head-delta", "done");
		assertThat(sink.of(Text.class).getFirst().text()).isEqualTo("네, 책첵은 무료예요.");
		assertThat(sink.last(Done.class).result()).isEqualTo("site");
		assertThat(sink.last(Done.class).bookIds()).isEmpty();
	}

	@Test
	void withTheMlFirstCallNotABookQuestionAsksNoModel() { // user 2026-10-26
		mlOn(java.util.Map.of("오늘 점심 뭐 먹지", com.chaekcheck.aisearch.internal.firstcall.TinyFirstCall.NOT_BOOK),
				List.of());

		run("오늘 점심 뭐 먹지", List.of());

		assertThat(llm.calls).isEmpty();
		assertThat(sink.last(Done.class).result()).isEqualTo("no_match");
	}

	@Test
	void withAskBackNotABookQuestionIsAnsweredByTheChatCall() { // user 2026-10-29 「책 밖 대화는 부드럽게」
		mlOn(java.util.Map.of("잠이 안 와", com.chaekcheck.aisearch.internal.firstcall.TinyFirstCall.NOT_BOOK), List.of(2L, 7L));
		askBackOn();
		llm.then(Answer.ok("{\"question\":\"잠이 안 오는 밤이시군요. 천천히 읽을 책은 어떠세요?\","
				+ "\"choices\":[\"잔잔한 에세이\",\"잠과 뇌를 다룬 과학책\"]}", 1, 1));

		run("잠이 안 와", List.of());

		assertThat(llm.calls).hasSize(1);
		assertThat(llm.calls.getFirst().system()).isEqualTo(Prompts.CHAT_SYSTEM);
		AiSearchEvents.Ask ask = sink.of(AiSearchEvents.Ask.class).getFirst();
		assertThat(ask.question()).isEqualTo("잠이 안 오는 밤이시군요. 천천히 읽을 책은 어떠세요?");
		assertThat(ask.choices()).containsExactly("잔잔한 에세이", "잠과 뇌를 다룬 과학책");
		assertThat(sink.last(Done.class).result()).isEqualTo("asked");
	}

	@Test
	void aFailedChatCallFallsBackToTheFixedAskBack() { // user 2026-10-29
		mlOn(java.util.Map.of("엄마", com.chaekcheck.aisearch.internal.firstcall.TinyFirstCall.NOT_BOOK), List.of(2L, 7L));
		askBackOn();
		llm.then(Answer.ok("not json", 1, 1));

		run("엄마", List.of());

		AiSearchEvents.Ask ask = sink.of(AiSearchEvents.Ask.class).getFirst();
		assertThat(ask.question()).isEqualTo(Prompts.NOT_BOOK_ASK);
		assertThat(ask.choices()).hasSize(3).allMatch(Prompts.NOT_BOOK_CHOICES::contains);
	}

	@Test
	void withTheMlFirstCallAFollowUpSearchesWithTheEarlierQuestionJoined() { // M4, user 2026-10-26
		float[] book = com.chaekcheck.aisearch.internal.firstcall.TinyFirstCall.BOOK;
		mlOn(java.util.Map.of("더 쉬운 걸로", book, "우주 과학 책", book, "우주 과학 책 더 쉬운 걸로", book), List.of(2L, 7L));
		llm.then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run("더 쉬운 걸로", List.of(new AiSearchFlow.Turn("우주 과학 책", List.of(1L, 4L))));

		assertThat(encoded).containsExactlyInAnyOrder("더 쉬운 걸로", "우주 과학 책", "우주 과학 책 더 쉬운 걸로");
		assertThat(llm.calls).hasSize(1);
		// the second call still gets the earlier turn
		assertThat(llm.calls.getFirst().messages().getFirst().text()).isEqualTo("우주 과학 책");
	}

	@Test
	void withTheMlFirstCallAFailedVectorAsksHaikuAsBefore() { // user 2026-10-26
		mlOn(java.util.Map.of(), List.of());
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run(QUESTION, List.of());

		assertThat(llm.calls).hasSize(2);
		assertThat(llm.calls.getFirst().system()).isEqualTo(Prompts.KEYWORD_SYSTEM);
	}

	/** Vectors that are on and answer with {@code nearest}. */
	static com.chaekcheck.aisearch.internal.vector.QuestionVectors vectorsGiving(
			java.util.concurrent.CompletableFuture<List<Long>> nearest) {
		return new com.chaekcheck.aisearch.internal.vector.QuestionVectors() {

			@Override
			public boolean on() {
				return true;
			}

			@Override
			public java.util.concurrent.CompletableFuture<List<Long>> nearest(String question, int limit) {
				return nearest;
			}

		};
	}

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
	void aFailedTabCoverReadLeavesTheCardsWithoutTabCovers() { // covers are read while the call runs (2026-10-27)
		java.util.function.Function<java.util.Collection<Long>, java.util.Map<Long, String>> failing = ids -> {
			throw new IllegalStateException("down");
		};
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"}]}"),
				1, 1));

		new AiSearchFlow(llm, candidates, budget, listedBooks(), new SearchBooks(searchIndex), neighbours, com.chaekcheck.aisearch.TestSearchBooks.texts(failing, blurbs),
				questionVectors, mlFirstCall, years(publishYears), pick, usageLog, properties, json)
			.run(input(QUESTION, List.of()), sink);

		assertThat(sink.of(Card.class)).hasSize(1);
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void everyCardTakesTheCatalogSummary() { // cycle 26: one summary kind, the list shows the same one
		stored.add(new Book(500, "우주 과학 교양", "칼 세이건", new long[] { 101, 102 }));
		texts.replace(stored, lines);
		tabBlurbs.put(500L, "별과 은하를 쉽게 풀어 쓴 교양서예요.");
		tabBlurbs.put(20L, "한 집안의 이야기를 그린 대하소설이에요.");
		tabBlurbs.put(1L, "과학 이야기 1의 책첵 요약");
		llm.then(keywords("과학", "토지", "우주"))
			.then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"},"
					+ "{\"no\":2,\"fit\":3,\"text\":\"나\"},{\"no\":3,\"fit\":3,\"text\":\"다\"}]}"), 1, 1));

		run(QUESTION, List.of());

		java.util.Map<Long, String> shown = new java.util.HashMap<>();
		sink.of(Card.class).forEach(card -> shown.put(card.bookId(), card.aiSummary()));
		assertThat(shown).containsEntry(500L, "별과 은하를 쉽게 풀어 쓴 교양서예요.")
			.containsEntry(20L, "한 집안의 이야기를 그린 대하소설이에요.")
			.containsEntry(1L, "과학 이야기 1의 책첵 요약"); // a listed book too: no list summary of its own
	}

	/** The single call that may ask back (user 2026-10-26). */
	private void askBackOn() {
		pick = new com.chaekcheck.aisearch.internal.config.PickProperties(true, true);
	}

	@Test
	void oneWordTopicsAreWordsOfTwoLettersOrMore() {
		assertThat(AiSearchFlow.oneWordTopic("엄마")).isTrue();
		assertThat(AiSearchFlow.oneWordTopic(" AI ")).isTrue();
		assertThat(AiSearchFlow.oneWordTopic("??")).isFalse();
		assertThat(AiSearchFlow.oneWordTopic("ㅋㅋㅋ")).isFalse();
		assertThat(AiSearchFlow.oneWordTopic("음 뭐 읽지")).isFalse();
	}

	@Test
	void aOneWordQuestionIsAnsweredAtOnceWithItsSensesMixed() { // user 2026-10-29 「한 단어도 그걸로 답변」
		pick = new com.chaekcheck.aisearch.internal.config.PickProperties(true, false);
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"\",\"picks\":[],\"tail\":\"\"}", 1, 1))
			.then(Answer.ok("{\"question\":\"과학의 어느 쪽이 궁금하세요?\",\"choices\":[\"쉬운 우주 과학책\",\"과학사 입문서\"]}", 1, 1));

		run("과학", List.of());

		LlmCall pickCall = llm.calls.get(1);
		assertThat(pickCall.system()).isEqualTo(Prompts.PICK_SYSTEM);
		assertThat(pickCall.messages().getLast().text()).contains("대화 메모: 질문이 낱말 하나(「과학」)다. 되묻지 않는다.")
			.doesNotContain("후보 분야");

		// two words may still be asked back
		llm.then(keywords("과학")).then(Answer.ok("{\"ask\":{\"question\":\"\",\"choices\":[]},\"head\":\"\",\"picks\":[],"
				+ "\"tail\":\"\"}", 1, 1));
		// no book: choices for this question go out as an ask-back (user 2026-10-30)
		assertThat(llm.calls.get(2).system()).isEqualTo(Prompts.NO_MATCH_SYSTEM);
		assertThat(sink.names()).contains("ask");
		flow().run(input("과학 책", List.of()), new Sink());
		assertThat(llm.calls.get(4).system()).isEqualTo(Prompts.PICK_ASK_SYSTEM);
	}

	@Test
	void theAnswerStreamsHeadThenEachCheckedCardAndItsText() {
		for (long id = 1; id <= 40; id++) {
			tabBlurbs.put(id, "과학 이야기 " + id + "의 책첵 요약");
		}
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
		// the 책첵 summary rides on the card for the book window (user 2026-10-24; catalog's since cycle 26)
		assertThat(cards.getFirst().aiSummary()).isEqualTo(cards.getFirst().title() + "의 책첵 요약");
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
	void withAskBackTheSingleCallAsksInsteadOfPicking() { // user 2026-10-26: ask-back in the single call
		askBackOn();
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"ask\":{\"question\":\"어느 쪽이에요?\",",
				"\"choices\":[\"그림 그리는 법\",\"명화 감상\",\" \"]},\"head\":\"무시\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"}]}"),
				1000, 60));

		run("과학 그림책", List.of());

		assertThat(sink.names()).containsExactly("ask", "done");
		AiSearchEvents.Ask ask = sink.of(AiSearchEvents.Ask.class).getFirst();
		assertThat(ask.question()).isEqualTo("어느 쪽이에요?");
		assertThat(ask.choices()).containsExactly("그림 그리는 법", "명화 감상");
		assertThat(sink.last(Done.class).result()).isEqualTo("asked");
		assertThat(llm.calls.getLast().system()).isEqualTo(Prompts.PICK_ASK_SYSTEM);
	}

	@Test
	void anAnswerToAnAskBackIsNotAskedBackAndTheCallSeesTheAskBack() { // user 2026-10-27
		askBackOn();
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"}]}"),
				1000, 60));

		run("인상주의", List.of(new AiSearchFlow.Turn("그림", List.of(), "어떤 그림 책을 찾으세요?", List.of("그리는 법", "명화 감상"))));

		LlmCall call = llm.calls.getLast();
		assertThat(call.system()).isEqualTo(Prompts.PICK_SYSTEM);
		assertThat(call.messages().get(1).text()).isEqualTo("되물은 말: 어떤 그림 책을 찾으세요?\n고를 답: 그리는 법 / 명화 감상");
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void theShelvesLineGoesUnderTheBooksWhenGiven() {
		String text = Prompts.pickMessages(List.of(), "사진", List.of(new Prompts.Choice("가", "나", "한 줄", 2020)), 2026,
				new java.util.LinkedHashMap<>(java.util.Map.of("예술·취미(비소설)", 9))).getLast().text();

		assertThat(text).endsWith("\n후보 분야: 예술·취미(비소설) 9권");
		assertThat(AiSearchFlow.words(" 사진  책 ")).isEqualTo(2);
	}

	@Test
	void withAskBackAQuestionJoiningEarlierTopicsIsNeverAskedBack() { // user 2026-10-26: 2 wrong asks in the test
		askBackOn();
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"head\":\"\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"}]}"),
				1000, 60));

		run("위에 말한 두 개 섞은 느낌", List.of(new AiSearchFlow.Turn("판타지 소설", List.of()),
				new AiSearchFlow.Turn("추리 소설", List.of())));

		assertThat(llm.calls.getLast().system()).isEqualTo(Prompts.PICK_SYSTEM);
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void withAskBackAnEmptyAskStreamsTheAnswerAsBefore() {
		askBackOn();
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"ask\":{\"question\":\"\",\"choices\":[]},",
				"\"head\":\"좋아요\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"}]}"), 1000, 60));

		run("과학 그림책", List.of());

		assertThat(sink.names()).containsExactly("head-delta", "card", "card-delta", "done");
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void withAskBackALongFirstQuestionGoesWithoutTheAskField() { // user 2026-10-27, 응답 시간 4안
		askBackOn();
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"head\":\"좋아요\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"}]}"),
				1000, 60));

		run(QUESTION, List.of());

		assertThat(AiSearchFlow.words(QUESTION)).isGreaterThan(AiSearchFlow.ASK_QUESTION_WORDS);
		assertThat(llm.calls.getLast().system()).isEqualTo(Prompts.PICK_SYSTEM);
		assertThat(llm.calls.getLast().jsonSchema()).isEqualTo(Prompts.PICK_SCHEMA);
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void withAskBackALongFollowUpMayStillBeAskedBack() { // user 2026-10-27, 응답 시간 4안
		askBackOn();
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"ask\":{\"question\":\"\",\"choices\":[]},",
				"\"head\":\"좋아요\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"}]}"), 1000, 60));

		run("이번엔 잠들기 전에 읽을 만한 가벼운 걸로", List.of(new AiSearchFlow.Turn("과학책 추천해줘", List.of(20L, 1L))));

		assertThat(llm.calls.getLast().system()).isEqualTo(Prompts.PICK_ASK_SYSTEM);
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void withAskBackANotBookQuestionGetsTheChatCallsAnswer() { // user 2026-10-29
		askBackOn();
		llm.then(Answer.ok("{\"notBook\":true,\"titleLike\":false,\"title\":\"\",\"refersToShown\":false,\"keywords\":[]}",
				900, 40));
		llm.then(Answer.ok("{\"question\":\"고민되시죠. 요즘 기분에 맞는 책부터 볼까요?\",\"choices\":[\"가벼운 소설\",\"짧은 에세이\",\"쉬운 과학책\"]}",
				300, 60));

		run("음 뭐 읽지", List.of());

		assertThat(sink.names()).containsExactly("ask", "done");
		AiSearchEvents.Ask ask = sink.of(AiSearchEvents.Ask.class).getFirst();
		assertThat(ask.question()).isEqualTo("고민되시죠. 요즘 기분에 맞는 책부터 볼까요?");
		assertThat(ask.choices()).containsExactly("가벼운 소설", "짧은 에세이", "쉬운 과학책");
		assertThat(llm.calls).hasSize(2);
		assertThat(llm.calls.get(1).system()).isEqualTo(Prompts.CHAT_SYSTEM);
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
		assertThat(pickCall.messages().get(1).text()).isEqualTo("보여 준 책:\n- 1번째 책: 토지 / 저자: 박경리\n- 2번째 책: 과학 이야기 1 / 저자: 저자 1");

		// not pointing at them: the books shown before are left out
		Sink other = new Sink();
		llm.then(keywords("과학", "토지")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));
		flow().run(input("다른 과학책", List.of(earlier)), other);
		assertThat(llm.calls.get(3).messages().getLast().text()).doesNotContain("토지", "과학 이야기 1 /");
	}

	@Test
	void theServerWritesTheSummaryWithTheCardsTitles() { // user 2026-10-29 「상황별 정리」, then 「바꿔 봐」 (structured)
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"골랐어요.\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"하나\"},"
				+ "{\"no\":2,\"fit\":3,\"text\":\"둘\"},{\"no\":3,\"fit\":1,\"text\":\"셋\"}],\"tail\":{\"lines\":["
				+ "{\"situation\":\"출퇴근길에\",\"nos\":[1,3]},{\"situation\":\"주말에\",\"nos\":[2,1]},"
				+ "{\"situation\":\"잠들기 전에\",\"nos\":[3]}],\"summary\":\"\"}}", 1, 1));

		run("과학책 추천해줘", List.of());

		assertThat(sink.names()).endsWith("card-delta", "tail-delta", "done");
		// number 3 was not shown (fit 1), book 1 goes in one line only, a line left without a book is dropped
		List<Long> shown = sink.last(Done.class).bookIds();
		String first = sink.of(Card.class).get(0).title();
		String second = sink.of(Card.class).get(1).title();
		assertThat(shown).hasSize(2);
		assertThat(sink.of(Text.class).getLast().text()).isEqualTo("출퇴근길에: 『" + first + "』\n주말에: 『" + second + "』");
	}

	@Test
	void withoutSituationsTheSummaryGoesOutWhenItNamesNoBook() { // user 2026-10-29
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"골랐어요.\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"하나\"},"
				+ "{\"no\":2,\"fit\":3,\"text\":\"둘\"}],\"tail\":{\"lines\":[],\"summary\":\"둘 다 쉬운 입문서예요.\"}}", 1, 1));

		run("과학책 추천해줘", List.of());

		assertThat(sink.of(Text.class).getLast().text()).isEqualTo("둘 다 쉬운 입문서예요.");

		Sink other = new Sink();
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"골랐어요.\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"하나\"},"
				+ "{\"no\":2,\"fit\":3,\"text\":\"둘\"}],\"tail\":{\"lines\":[],\"summary\":\"1번부터 보세요.\"}}", 1, 1));
		flow().run(input("과학책 추천해줘", List.of()), other);
		assertThat(other.names()).doesNotContain("tail-delta");
	}

	@Test
	void aSummaryRunningOnIsCutAndTheAnswerStaysWhole() { // production v1.21.1: an 8-card answer stopped at its 15 s
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"골랐어요.\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"하나\"},"
				+ "{\"no\":2,\"fit\":3,\"text\":\"둘\"}],\"tail\":{\"lines\":[],\"summary\":\""
				+ "가".repeat(AiSearchFlow.TAIL_MAX_CHARS + 50) + "\"}}", 1, 1));

		run("과학책 추천해줘", List.of());

		assertThat(sink.names()).doesNotContain("tail-delta", "error");
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	@Test
	void noSummaryGoesOutForOneCard() { // user 2026-10-29
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"골랐어요.\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"하나\"}],"
				+ "\"tail\":{\"lines\":[],\"summary\":\"한 권뿐이에요.\"}}", 1, 1));

		run("과학책 추천해줘", List.of());

		assertThat(sink.names()).doesNotContain("tail-delta");
	}

	@Test
	void anEarlierHeadGoesBackWithItsTurn() { // user 2026-10-29 「대화 기억」
		AiSearchFlow.Turn earlier = new AiSearchFlow.Turn("과학책 추천해줘", List.of(20L, 1L), null, List.of(),
				"쉬운 과학책을 골랐어요.");
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"\",\"picks\":[],\"tail\":\"\"}", 1, 1));

		run("두 번째 책 어때?", List.of(earlier));

		assertThat(llm.calls.get(1).messages().get(1).text())
			.isEqualTo("머리말: 쉬운 과학책을 골랐어요.\n보여 준 책:\n- 1번째 책: 토지 / 저자: 박경리\n- 2번째 책: 과학 이야기 1 / 저자: 저자 1");
	}

	@Test
	void aQuestionThatIsATitleGetsThatBookFirstAndIsNotAskedBack() { // user 2026-10-29 「해리포터」
		askBackOn();
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":true,\"title\":\"과학 이야기\",\"refersToShown\":false,"
				+ "\"keywords\":[\"소설\"]}", 1, 1)).then(Answer.ok("{\"head\":\"\",\"picks\":[],\"tail\":\"\"}", 1, 1));

		run("과학 이야기", List.of());

		LlmCall pickCall = llm.calls.get(1);
		assertThat(pickCall.system()).isEqualTo(Prompts.PICK_SYSTEM);
		assertThat(pickCall.messages().getLast().text()).contains("대화 메모: 사용자가 쓴 「과학 이야기」는 책 제목이다",
				"1. 제목: 과학 이야기 1 /");
	}

	@Test
	void aShortWordThatIsAlsoATitleIsATopicAndMayBeAskedBack() { // user 2026-10-29, ask_yes 15/16 → 7/16 otherwise
		askBackOn();
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":true,\"title\":\"토지\",\"refersToShown\":false,"
				+ "\"keywords\":[\"과학\"]}", 1, 1)).then(Answer.ok("{\"head\":\"\",\"picks\":[],\"tail\":\"\"}", 1, 1));

		run("토지", List.of());

		LlmCall pickCall = llm.calls.get(1);
		assertThat(pickCall.system()).isEqualTo(Prompts.PICK_ASK_SYSTEM);
		assertThat(pickCall.messages().getLast().text()).doesNotContain("대화 메모");
	}

	@Test
	void moreOfAQualityKeepsTheLastBooksButMoreBooksDoesNot() { // user 2026-10-29 「더 쉬운 거」 / 「더 보여줘」
		assertThat(AiSearchFlow.MORE_SO.matcher("더 쉬운 거").find()).isTrue();
		assertThat(AiSearchFlow.MORE_SO.matcher("더 짧은 걸로").find()).isTrue();
		assertThat(AiSearchFlow.MORE_SO.matcher("좀 더 현실적인 걸로").find()).isTrue();
		assertThat(AiSearchFlow.MORE_SO.matcher("더 보여줘").find()).isFalse();
		assertThat(AiSearchFlow.MORE_SO.matcher("다른 거 더").find()).isFalse();
	}

	@Test
	void aQuestionNamingABookGetsItsNeighboursFirst() { // user 2026-10-25, report 3-3
		// 토지's neighbours: 3 (held by 999 only, not in the plan), 4, 5, then 20 itself would never come
		neighbourMap.put(20L, List.of(3L, 4L, 5L));
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run("토지 같은 소설", List.of());

		String sent = llm.calls.get(1).messages().getLast().text();
		assertThat(sent).contains("1. 제목: 과학 이야기 4 /", "2. 제목: 과학 이야기 5 /", "3. 제목: 과학 이야기 1 /")
			.doesNotContain("과학 이야기 3 /", "제목: 토지 /");
	}

	@Test
	void theQuestionVectorsAreMixedWithTheKeywordsByRank() { // user 2026-10-25, report H
		stored.add(new Book(500, "우주 교양", "칼 세이건", new long[] { 101 }));
		stored.add(new Book(501, "먼 별", "저자", new long[] { 999 }));
		texts.replace(stored, lines);
		// 501 is held by no plan library; 7 is in both lists, so it comes first; 1 and 500 tie, the keyword one first
		questionVectors = vectorsGiving(java.util.concurrent.CompletableFuture.completedFuture(List.of(501L, 500L, 7L)));
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run(QUESTION, List.of());

		String sent = llm.calls.get(1).messages().getLast().text();
		assertThat(sent).contains("1. 제목: 과학 이야기 7 /", "2. 제목: 과학 이야기 1 /", "3. 제목: 우주 교양 /",
				"4. 제목: 과학 이야기 2 /")
			.doesNotContain("먼 별");
	}

	@Test
	void aFailingQuestionVectorLeavesTheKeywordsAlone() {
		questionVectors = vectorsGiving(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("x")));
		llm.then(keywords("과학")).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run(QUESTION, List.of());

		assertThat(llm.calls.get(1).messages().getLast().text()).contains("1. 제목: 과학 이야기 1 /",
				"2. 제목: 과학 이야기 2 /");
		assertThat(sink.names()).contains("done");
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
	void aFollowUpPointingBackAlsoGetsTheBooksOfTheTurnsBeforeTheLast() { // user 2026-10-26 "위에 언급한 두 개"
		AiSearchFlow.Turn first = new AiSearchFlow.Turn("과학책 추천해줘", List.of(1L, 2L));
		AiSearchFlow.Turn last = new AiSearchFlow.Turn("소설은", List.of(20L));
		llm.then(Answer.ok("{\"notBook\":false,\"titleLike\":false,\"title\":\"\",\"refersToShown\":true,"
				+ "\"keywords\":[]}", 1, 1)).then(Answer.ok("{\"head\":\"\",\"picks\":[]}", 1, 1));

		run("위에 말한 두 개에 관한 책", List.of(first, last));

		// the last turn's book first, then the earlier turn's books in their order
		assertThat(llm.calls.get(1).messages().getLast().text()).contains("1. 제목: 토지", "2. 제목: 과학 이야기 1 /",
				"3. 제목: 과학 이야기 2 /");
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
		AiSearchFlow broken = new AiSearchFlow(llm, candidates, budget, listedBooks(), new SearchBooks(searchIndex), neighbours, com.chaekcheck.aisearch.TestSearchBooks.texts(covers, blurbs), questionVectors, MlFirstCall.off(), years(publishYears), pick, failing, properties,
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

	@Test
	void talkWordsGoOnFromTheTopicTheyPointAt() { // plan 2026-10-29, A–E
		AiSearchFlow.Turn ai = new AiSearchFlow.Turn("AI 관련 기초 도서", List.of(1L, 2L));
		AiSearchFlow.Turn photo = new AiSearchFlow.Turn("사진", List.of(), "어떤 사진 책을 찾으세요?", List.of("찍는 법", "사진집"));
		AiSearchFlow.Turn cooking = new AiSearchFlow.Turn("요리", List.of(3L));

		AiSearchFlow.Talk dontKnow = AiSearchFlow.talk(input("나도 잘 모르겠어", List.of(ai, photo)));
		assertThat(dontKnow.topic()).isEqualTo("사진");
		assertThat(dontKnow.mode()).isEqualTo(AiSearchFlow.AskMode.NEVER);
		assertThat(dontKnow.note()).contains("「찍는 법」·「사진집」");

		AiSearchFlow.Talk notThatAfterAsk = AiSearchFlow.talk(input("그거 말고", List.of(ai, photo)));
		assertThat(notThatAfterAsk.mode()).isEqualTo(AiSearchFlow.AskMode.FORCE);
		assertThat(notThatAfterAsk.topic()).isEqualTo("사진");

		AiSearchFlow.Talk notThatAfterCards = AiSearchFlow.talk(input("그거 말고", List.of(ai, cooking)));
		assertThat(notThatAfterCards.topic()).isEqualTo("요리");
		assertThat(notThatAfterCards.excludeShown()).isTrue();

		AiSearchFlow.Talk back = AiSearchFlow.talk(input("아까 이야기한 거", List.of(ai, cooking)));
		assertThat(back.topic()).isEqualTo("AI 관련 기초 도서");
		assertThat(back.mode()).isEqualTo(AiSearchFlow.AskMode.NEVER);
		assertThat(back.excludeShown()).isFalse();

		AiSearchFlow.Talk unknown = AiSearchFlow.talk(input("그거 말고 저거", List.of(ai, cooking)));
		assertThat(unknown.mode()).isEqualTo(AiSearchFlow.AskMode.FORCE);
		assertThat(unknown.note()).contains("「AI 관련 기초 도서」, 「요리」");

		assertThat(AiSearchFlow.talk(input("추리 소설", List.of(ai)))).isNull();
		assertThat(AiSearchFlow.talk(input("그거 말고", List.of()))).isNull();
	}

	@Test
	void notThatAfterAnAskBackAsksAgainWithTheNoteUnderTheQuestion() { // plan 2026-10-29, B
		askBackOn();
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"ask\":{\"question\":\"다른 쪽인가요?\",\"choices\":[\"가\",\"나\"]},",
				"\"head\":\"\",\"picks\":[]}"), 1000, 60));

		run("그거 말고", List.of(new AiSearchFlow.Turn("과학책 추천해줘", List.of(), "어떤 과학책이요?", List.of("우주", "생물"))));

		LlmCall call = llm.calls.getLast();
		assertThat(call.system()).isEqualTo(Prompts.PICK_ASK_SYSTEM);
		assertThat(call.messages().getLast().text()).contains("\n대화 메모: ").contains("「우주」·「생물」");
		assertThat(sink.last(Done.class).result()).isEqualTo("asked");
	}

	@Test
	void notKnowingAfterCardsIsNotAskedBackEither() { // user 2026-10-29: a one-word question shows cards at once
		AiSearchFlow.Talk talk = AiSearchFlow.talk(input("나도 잘 모르겠어",
				List.of(new AiSearchFlow.Turn("사진", List.of(1L, 2L)))));

		assertThat(talk.mode()).isEqualTo(AiSearchFlow.AskMode.NEVER);
		assertThat(talk.topic()).isEqualTo("사진");
		assertThat(talk.excludeShown()).isFalse();
		assertThat(AiSearchFlow.talk(input("모르겠어", List.of(new AiSearchFlow.Turn("사진", List.of()))))).isNull();
	}

	@Test
	void notKnowingAfterAnAskBackIsNeverAskedAgain() { // plan 2026-10-29, A
		askBackOn();
		llm.then(keywords("과학")).then(Answer.pieces(List.of("{\"head\":\"좋아요\",\"picks\":[{\"no\":1,\"fit\":3,\"text\":\"가\"}]}"),
				1000, 60));

		run("잘 모르겠어요", List.of(new AiSearchFlow.Turn("과학책 추천해줘", List.of(), "어떤 과학책이요?", List.of("우주", "생물"))));

		assertThat(llm.calls.getLast().system()).isEqualTo(Prompts.PICK_SYSTEM);
		assertThat(sink.last(Done.class).result()).isEqualTo("ok");
	}

	private AiSearchFlow.Input input(String question, List<AiSearchFlow.Turn> history) {
		return new AiSearchFlow.Input(question, history.size() + 1, history, Set.of(101L, 102L), REQUEST,
				System.nanoTime());
	}

	private AiSearchFlow flow() {
		return new AiSearchFlow(llm, candidates, budget, listedBooks(), new SearchBooks(searchIndex), neighbours, com.chaekcheck.aisearch.TestSearchBooks.texts(covers, blurbs), questionVectors, mlFirstCall, years(publishYears), pick, usageLog,
				flowProperties == null ? properties : flowProperties, json);
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
