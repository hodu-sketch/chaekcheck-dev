package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.TestSearchBooks;
import com.chaekcheck.aisearch.TestSearchBooks.Book;
import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.config.PickProperties;
import com.chaekcheck.aisearch.internal.firstcall.MlFirstCall;
import com.chaekcheck.aisearch.internal.flow.FakeLlm.Answer;
import com.chaekcheck.aisearch.internal.llm.AnthropicLlmClient;
import com.chaekcheck.aisearch.internal.llm.AnthropicProperties;
import com.chaekcheck.aisearch.internal.llm.LlmClient;
import com.chaekcheck.aisearch.internal.match.CandidateQuery;
import com.chaekcheck.aisearch.internal.match.SearchBooks;
import com.chaekcheck.aisearch.internal.quota.DailyBudget;
import com.chaekcheck.aisearch.internal.vector.KureQuestionVectorsAccess;
import com.chaekcheck.catalog.BookProfiles;
import com.chaekcheck.catalog.BookProfiles.Profile;
import com.chaekcheck.catalog.BookTextsTransfer.Line;
import com.chaekcheck.catalog.BookTextsTransfer.Source;
import com.chaekcheck.catalog.BookTextsTransfer.Written;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * Step 6 of the two layers (user 2026-10-30 "6단계 그거 기억하고 계획대로 가자", docs/maintenance/6-aisearch.md 15):
 * the questions of docs/aisearch/reports/2026-10-29-intro-text.md through the real {@link AiSearchFlow} over every AI
 * search book, with the plan libraries' filter, the ML first call with or without the book profiles, and either every
 * candidate as a card in the order the pick call gets them ({@code candidates}) or Haiku's real picks ({@code picks}).
 * <p>
 * Runs only on its own database ({@code TEST_DB_PORT} of the eval container, never db-test: the load commits) when
 * {@code AISEARCH_EVAL2_DIR} is set:
 * <ul>
 * <li>{@code AISEARCH_EVAL2_STEP=load}: {@code books.csv} (id, title, author, library ids, line), {@code
 * summary-terms.tsv} and {@code book-profiles.tsv} of that folder into the search books and the profiles</li>
 * <li>{@code AISEARCH_EVAL2_STEP=run}: {@code AISEARCH_MODEL_DIR} (its {@code book-vectors.bin} and first-call model),
 * {@code AISEARCH_EVAL2_SEARCH} exact|funnel, {@code AISEARCH_EVAL2_PROFILES} on|off, {@code AISEARCH_EVAL2_MODE}
 * candidates|picks, the keyword match and reading text by their usual environment variables; writes {@code
 * out-<AISEARCH_EVAL2_NAME>.json}</li>
 * </ul>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AISEARCH_EVAL2_DIR", matches = ".+")
class AiSearchTwoLayerEvalTests {

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

	@Test
	void evaluate() throws Exception {
		Path dir = Path.of(System.getenv("AISEARCH_EVAL2_DIR"));
		if ("load".equals(System.getenv("AISEARCH_EVAL2_STEP"))) {
			load(dir);
		}
		else {
			run(dir);
		}
	}

	private void load(Path dir) throws Exception {
		List<Book> books = new ArrayList<>();
		List<Line> lines = new ArrayList<>();
		try (BufferedReader in = Files.newBufferedReader(dir.resolve("books.csv"), StandardCharsets.UTF_8)) {
			for (List<String> row : AiSearchEvalReplayTests.Csv.rows(in)) {
				long id = Long.parseLong(row.get(0));
				long[] libraries = row.get(3).isEmpty() ? new long[0]
						: Arrays.stream(row.get(3).split(",")).mapToLong(Long::parseLong).toArray();
				books.add(new Book(id, row.get(1), row.get(2).isEmpty() ? null : row.get(2), libraries));
				if (!row.get(4).isEmpty()) {
					lines.add(new Line(id, Source.INTRO, LocalDate.of(2026, 10, 25), row.get(4)));
				}
			}
		}
		Set<Long> stored = new HashSet<>();
		books.forEach(book -> stored.add(book.bookId()));
		List<Written> written = new ArrayList<>();
		List<String> rows = Files.readAllLines(dir.resolve("summary-terms.tsv"), StandardCharsets.UTF_8);
		for (String row : rows.subList(1, rows.size())) {
			String[] cell = row.split("\t", -1);
			long id = Long.parseLong(cell[0]);
			if (stored.contains(id)) {
				written.add(new Written(id, LocalDate.parse(cell[3]), cell[1], cell[2]));
			}
		}
		var result = texts.replace(books, lines, written);
		System.out.println("eval2 load: " + books.size() + " books, " + lines.size() + " lines, " + written.size()
				+ " written: " + result);
	}

	private void run(Path dir) throws Exception {
		String name = System.getenv("AISEARCH_EVAL2_NAME");
		boolean picks = "picks".equals(System.getenv("AISEARCH_EVAL2_MODE"));
		boolean profilesOn = !"off".equals(System.getenv("AISEARCH_EVAL2_PROFILES"));
		AiSearchProperties.SearchMode mode = AiSearchProperties.SearchMode
			.valueOf(System.getenv("AISEARCH_EVAL2_SEARCH").toUpperCase(java.util.Locale.ROOT));
		AiSearchProperties.Vector v = properties.vector();
		AiSearchProperties.Vector settings = new AiSearchProperties.Vector(true, System.getenv("AISEARCH_MODEL_DIR"),
				v.threads(), v.keywordDepth(), v.vectorDepth(), v.rrfK(), Duration.ofSeconds(30),
				AiSearchProperties.Encoder.LOCAL, new AiSearchProperties.Remote("", "", Duration.ofSeconds(2)), 3,
				Duration.ofSeconds(30), new AiSearchProperties.Search(mode, 10000, 2000));
		AiSearchProperties.FirstCall firstCall = new AiSearchProperties.FirstCall(AiSearchProperties.FirstCallMode.ML,
				properties.firstCall().shelfBonus(), properties.firstCall().siteMaxTokens(), AiSearchProperties.FollowUp.RELATION);
		// candidates: every candidate becomes a card, in the order the pick call gets them
		AiSearchProperties.Limits l = properties.limits();
		AiSearchProperties.Limits limits = picks ? l
				: new AiSearchProperties.Limits(l.candidates(), l.candidates(), l.historyTurns(), l.questionMaxLength(),
						l.cardTextMaxChars());
		AiSearchProperties on = new AiSearchProperties(true, properties.zone(), properties.keywordTimeout(),
				Duration.ofSeconds(60), properties.keywordMaxTokens(), properties.pickMaxTokens(),
				properties.maxConcurrent(), properties.rate(), properties.budget(), properties.price(), limits, settings,
				firstCall);
		MlFirstCall mlFirstCall = new MlFirstCall(on, json, profiles(dir, profilesOn));
		assertThat(mlFirstCall.on()).isTrue();
		if ("shelves".equals(System.getenv("AISEARCH_EVAL2_STEP"))) {
			shelves(dir, on, mlFirstCall);
			return;
		}
		Set<Long> plan = new HashSet<>();
		for (JsonNode library : json.readTree(Files.readString(dir.resolve("libs-1114.json"))).get("libraries")) {
			String eligibility = library.get("eligibility").asString();
			if (eligibility.equals("ELIGIBLE") || eligibility.equals("OPEN")) {
				plan.add(library.get("id").asLong());
			}
		}
		StringBuilder all = new StringBuilder("{\"head\":\"\",\"picks\":[");
		for (int no = 1; no <= on.limits().candidates(); no++) {
			all.append(no == 1 ? "" : ",").append("{\"no\":").append(no).append(",\"fit\":3,\"text\":\"x\"}");
		}
		String allPicks = all.append("]}").toString();
		LlmClient haiku = picks ? haiku() : null;
		Map<String, Object> out = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
		JsonNode questions = json.readTree(Files.readString(dir.resolve("questions.json")));
		// picks: the Haiku call is most of the time, so several questions at once (the ms then include the waiting)
		int threads = picks ? Integer.parseInt(System.getenv().getOrDefault("AISEARCH_EVAL2_THREADS", "6")) : 1;
		try (var vectors = new KureQuestionVectorsAccess(on).open();
				var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
			List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
			for (JsonNode question : questions) {
				futures.add(pool.submit(() -> {
				String id = question.get("id").asString();
				AiSearchFlowTests.Sink sink = new AiSearchFlowTests.Sink();
				LlmClient llm = picks ? haiku
						: new FakeLlm().then(Answer.ok(allPicks, 1, 1)).then(Answer.ok(allPicks, 1, 1));
				AiSearchFlow flow = new AiSearchFlow(llm, candidates, budget, empty(), new SearchBooks(searchIndex),
						ids -> Map.of(), TestSearchBooks.texts(ids -> Map.of(), ids -> Map.of()), vectors.get(),
						mlFirstCall, AiSearchFlowTests.years(Map.of()), new PickProperties(false, false),
						new AiSearchFlowTests.RecordingLog(), on, json);
				long started = System.nanoTime();
				flow.run(new AiSearchFlow.Input(question.get("q").asString(), 1, List.of(), plan,
						new UsageRequest(Instant.now(), null, "login_off", null, null, true), started), sink);
				Map<String, Object> got = new LinkedHashMap<>();
				got.put("ms", (System.nanoTime() - started) / 1_000_000);
				got.put("books", sink.of(AiSearchEvents.Card.class).stream().map(AiSearchEvents.Card::bookId).toList());
				out.put(id, got);
				return null;
				}));
			}
			for (var future : futures) {
				future.get();
			}
		}
		Map<String, Object> ordered = new LinkedHashMap<>();
		for (JsonNode question : questions) {
			ordered.put(question.get("id").asString(), out.get(question.get("id").asString()));
		}
		Files.writeString(dir.resolve("out-" + name + ".json"), json.writeValueAsString(ordered));
		System.out.println("eval2 run " + name + ": " + out.size() + " questions");
	}

	/**
	 * Why the profiles help or not (user 2026-10-31 "프로필은 왜 이득이 없게 나왔을까?"): per question the shelves the ML
	 * first call reads, and for the asked book and every book of the {@code AISEARCH_EVAL2_LISTS} runs' lists the shared
	 * shelves (bonus / shelf bonus) and whether the question rules it out. Writes {@code shelves.json}.
	 */
	private void shelves(Path dir, AiSearchProperties on, MlFirstCall ml) throws Exception {
		List<JsonNode> lists = new ArrayList<>();
		for (String run : System.getenv("AISEARCH_EVAL2_LISTS").split(",")) {
			lists.add(json.readTree(Files.readString(dir.resolve("out-" + run + ".json"))));
		}
		double unit = on.firstCall().shelfBonus();
		Map<String, Object> out = new LinkedHashMap<>();
		try (var vectors = new KureQuestionVectorsAccess(on).open()) {
			for (JsonNode question : json.readTree(Files.readString(dir.resolve("questions.json")))) {
				String id = question.get("id").asString();
				String q = question.get("q").asString();
				float[] v = vectors.get().vectors(List.of(q)).get().get(0);
				MlFirstCall.Plan plan = ml.plan(q, v, null, null, null);
				Set<Long> books = new HashSet<>();
				if (question.has("book_id")) {
					books.add(question.get("book_id").asLong());
				}
				for (JsonNode list : lists) {
					list.get(id).get("books").forEach(b -> books.add(b.asLong()));
				}
				Map<String, Object> per = new LinkedHashMap<>();
				for (long book : books) {
					per.put(Long.toString(book), List.of(Math.round(ml.bonus(book, plan.shelves()) / unit),
							ml.rulesOut(book, plan.shelves())));
				}
				Map<String, Object> got = new LinkedHashMap<>();
				got.put("shelves", plan.shelves());
				got.put("books", per);
				out.put(id, got);
			}
		}
		Files.writeString(dir.resolve("shelves.json"), json.writeValueAsString(out));
		System.out.println("eval2 shelves: " + out.size() + " questions");
	}

	/** The profiles of {@code book-profiles.tsv}, or (off) one profile of no book, so none matches or rules out. */
	private static BookProfiles profiles(Path dir, boolean on) throws Exception {
		Map<Long, Profile> byId = new HashMap<>();
		if (on) {
			for (String row : Files.readAllLines(dir.resolve("book-profiles.tsv"), StandardCharsets.UTF_8)) {
				String[] c = row.split("\t", -1);
				if (c.length != 9 || !c[0].matches("\\d+")) {
					continue;
				}
				Boolean fiction = c[3].equals("1") ? Boolean.TRUE : c[3].equals("0") ? Boolean.FALSE : null;
				byId.put(Long.parseLong(c[0]), new Profile(text(c[1]), text(c[2]), fiction, list(c[4]), text(c[5]),
						text(c[6]), text(c[7]), list(c[8])));
			}
		}
		else {
			byId.put(-1L, new Profile(null, null, null, List.of(), null, null, null, List.of()));
		}
		return new BookProfiles() {

			@Override
			public Map<Long, Profile> all() {
				return byId;
			}

			@Override
			public String version() {
				return on ? "file" : "off";
			}

		};
	}

	private static String text(String cell) {
		return cell.isEmpty() ? null : cell;
	}

	private static List<String> list(String cell) {
		return cell.isEmpty() ? List.of() : Arrays.asList(cell.split("\\|"));
	}

	/** The real Haiku with the production call settings; the key comes from the environment and is never printed. */
	private LlmClient haiku() {
		String key = System.getenv("CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY");
		assertThat(key).as("CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY").isNotBlank();
		AnthropicProperties anthropic = new AnthropicProperties(key, "claude-haiku-5-5", null, null, "low", "disabled",
				true);
		return new AnthropicLlmClient(anthropic, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
				json);
	}

	private static ListedBooks empty() {
		return new ListedBooks() {

			@Override
			public Snapshot current() {
				return new Snapshot(Instant.EPOCH, List.of());
			}

			@Override
			public Snapshot loadFromDb() {
				return current();
			}

		};
	}

}
