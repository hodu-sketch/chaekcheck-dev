package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.TestSearchBooks;
import com.chaekcheck.aisearch.TestSearchBooks.Book;
import com.chaekcheck.catalog.BookTextsTransfer.Line;
import com.chaekcheck.catalog.BookTextsTransfer.Source;
import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.flow.FakeLlm.Answer;
import com.chaekcheck.aisearch.internal.match.CandidateQuery;
import com.chaekcheck.aisearch.internal.match.SearchBooks;
import com.chaekcheck.aisearch.internal.quota.DailyBudget;
import com.chaekcheck.aisearch.internal.vector.QuestionVectors;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * The evaluation questions of docs/aisearch/reports/2026-10-25-eval.md through the real {@link AiSearchFlow}: the
 * 30,939 search books of {@code sb.csv} put into db-test (rolled back), the neighbours of {@code nb.csv}, the fixed
 * first-call answers of the evaluation, the real question vectors, the plan of {@code libs-1114.json}. Writes the
 * book ids each question sends to the second call, in order, ({@code replay-<mode>.json} in the evaluation folder) so the script
 * can set them beside the measured variants. Runs only when {@code AISEARCH_EVAL_DIR} and {@code AISEARCH_MODEL_DIR}
 * are set; the vectors are on unless {@code AISEARCH_REPLAY_VECTORS=off}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "AISEARCH_EVAL_DIR", matches = ".+")
class AiSearchEvalReplayTests {

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

	@Test
	void replayTheEvaluationQuestions() throws Exception {
		Path eval = Path.of(System.getenv("AISEARCH_EVAL_DIR"));
		boolean vectorsOn = !"off".equals(System.getenv("AISEARCH_REPLAY_VECTORS"));
		List<Book> books = new ArrayList<>();
		List<Line> lines = new ArrayList<>();
		try (BufferedReader in = Files.newBufferedReader(eval.resolve("sb.csv"), StandardCharsets.UTF_8)) {
			for (List<String> row : Csv.rows(in)) {
				long id = Long.parseLong(row.get(0));
				long[] libraries = row.get(5).isEmpty() ? new long[0]
						: java.util.Arrays.stream(row.get(5).split(",")).mapToLong(Long::parseLong).toArray();
				books.add(new Book(id, row.get(1), row.get(2).isEmpty() ? null : row.get(2), libraries));
				if (!row.get(3).isEmpty()) {
					lines.add(new Line(id, Source.INTRO, LocalDate.of(2026, 10, 25), row.get(3)));
				}
			}
		}
		texts.replace(books, lines);
		Map<Long, List<Long>> neighbourMap = new HashMap<>();
		for (String row : Files.readAllLines(eval.resolve("nb.csv"))) {
			String[] parts = row.split(",", 2);
			List<Long> ids = new ArrayList<>();
			for (String id : parts[1].replace("\"", "").split(",")) {
				if (!id.isBlank()) {
					ids.add(Long.parseLong(id.trim()));
				}
			}
			neighbourMap.put(Long.parseLong(parts[0]), ids);
		}
		Set<Long> plan = new java.util.HashSet<>();
		for (JsonNode library : json.readTree(Files.readString(eval.resolve("libs-1114.json"))).get("libraries")) {
			String eligibility = library.get("eligibility").asString();
			if (eligibility.equals("ELIGIBLE") || eligibility.equals("OPEN")) {
				plan.add(library.get("id").asLong());
			}
		}
		Map<Integer, JsonNode> keywordAnswers = new HashMap<>();
		for (String name : List.of("kw-base.json", "kw-sim.json")) {
			for (JsonNode item : json.readTree(Files.readString(eval.resolve(name)))) {
				keywordAnswers.put(item.get("id").asInt(), item.get("out"));
			}
		}
		AiSearchProperties.Vector settings = new AiSearchProperties.Vector(vectorsOn, System.getenv("AISEARCH_MODEL_DIR"),
				2, 50, 60, 60, java.time.Duration.ofSeconds(30), AiSearchProperties.Encoder.LOCAL,
				new AiSearchProperties.Remote("", "", java.time.Duration.ofSeconds(2)), 3, java.time.Duration.ofSeconds(30),
				new AiSearchProperties.Search(AiSearchProperties.SearchMode.EXACT, 10000, 2000));
		AiSearchProperties on = new AiSearchProperties(true, properties.zone(), properties.keywordTimeout(),
				properties.pickTimeout(), properties.keywordMaxTokens(), properties.pickMaxTokens(),
				properties.maxConcurrent(), properties.rate(), properties.budget(), properties.price(),
				new AiSearchProperties.Limits(properties.limits().candidates(), properties.limits().candidates(),
						properties.limits().historyTurns(), properties.limits().questionMaxLength(),
						properties.limits().cardTextMaxChars()),
				settings, properties.firstCall());
		// every candidate becomes a card, in the order the second call got them, so the ids can be read back
		StringBuilder all = new StringBuilder("{\"head\":\"\",\"picks\":[");
		for (int no = 1; no <= properties.limits().candidates(); no++) {
			all.append(no == 1 ? "" : ",").append("{\"no\":").append(no).append(",\"fit\":3,\"text\":\"x\"}");
		}
		String allPicks = all.append("]}").toString();
		Map<String, List<Long>> out = new LinkedHashMap<>();
		try (var vectors = new com.chaekcheck.aisearch.internal.vector.KureQuestionVectorsAccess(on).open()) {
			QuestionVectors questionVectors = vectors.get();
			assertThat(questionVectors.on()).isEqualTo(vectorsOn);
			for (JsonNode question : json.readTree(Files.readString(eval.resolve("eval_questions.json")))) {
				int id = question.get("id").asInt();
				AiSearchFlowTests.Sink sink = new AiSearchFlowTests.Sink();
				FakeLlm llm = new FakeLlm().then(Answer.ok(json.writeValueAsString(keywordAnswers.get(id)), 1, 1))
					.then(Answer.ok(allPicks, 1, 1));
				AiSearchFlow flow = new AiSearchFlow(llm, candidates, budget, empty(), new SearchBooks(searchIndex), ids -> {
					Map<Long, List<Long>> found = new HashMap<>();
					ids.forEach(b -> {
						if (neighbourMap.containsKey(b)) {
							found.put(b, neighbourMap.get(b));
						}
					});
					return found;
				}, com.chaekcheck.aisearch.TestSearchBooks.texts(ids -> Map.of(), ids -> Map.of()), questionVectors, com.chaekcheck.aisearch.internal.firstcall.MlFirstCall.off(), AiSearchFlowTests.years(Map.of()), new com.chaekcheck.aisearch.internal.config.PickProperties(false, false), new AiSearchFlowTests.RecordingLog(), on, json);
				flow.run(new AiSearchFlow.Input(question.get("q").asString(), 1, List.of(), plan,
						new UsageRequest(Instant.now(), null, "login_off", null, null, true), System.nanoTime()),
						sink);
				out.put(String.valueOf(id),
						sink.of(AiSearchEvents.Card.class).stream().map(AiSearchEvents.Card::bookId).toList());
			}
		}
		Files.writeString(eval.resolve("replay-" + (vectorsOn ? "on" : "off") + ".json"), json.writeValueAsString(out));
		assertThat(out).hasSize(57);
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

	/** RFC 4180 rows as PostgreSQL's {@code \copy … csv} writes them: quotes around fields with commas or quotes. */
	static final class Csv {

		static List<List<String>> rows(BufferedReader in) throws java.io.IOException {
			List<List<String>> rows = new ArrayList<>();
			List<String> row = new ArrayList<>();
			StringBuilder field = new StringBuilder();
			boolean quoted = false;
			int c;
			while ((c = in.read()) != -1) {
				char ch = (char) c;
				if (quoted) {
					if (ch == '"') {
						in.mark(1);
						int next = in.read();
						if (next == '"') {
							field.append('"');
						}
						else {
							quoted = false;
							if (next != -1) {
								in.reset();
							}
						}
					}
					else {
						field.append(ch);
					}
				}
				else if (ch == '"') {
					quoted = true;
				}
				else if (ch == ',') {
					row.add(field.toString());
					field.setLength(0);
				}
				else if (ch == '\n') {
					row.add(field.toString());
					field.setLength(0);
					rows.add(row);
					row = new ArrayList<>();
				}
				else if (ch != '\r') {
					field.append(ch);
				}
			}
			if (!row.isEmpty() || field.length() > 0) {
				row.add(field.toString());
				rows.add(row);
			}
			return rows;
		}

	}

}
