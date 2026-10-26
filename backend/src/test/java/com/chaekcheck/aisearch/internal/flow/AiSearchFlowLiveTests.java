package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Card;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Done;
import com.chaekcheck.aisearch.internal.flow.AiSearchEvents.Text;
import com.chaekcheck.aisearch.internal.flow.AiSearchFlowTests.RecordingLog;
import com.chaekcheck.aisearch.internal.flow.AiSearchFlowTests.Sink;
import com.chaekcheck.aisearch.internal.llm.AnthropicLlmClient;
import com.chaekcheck.aisearch.internal.llm.AnthropicProperties;
import com.chaekcheck.aisearch.internal.match.CandidateQuery;
import com.chaekcheck.aisearch.internal.match.SearchBooks;
import com.chaekcheck.aisearch.internal.quota.DailyBudget;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * The whole flow with the real Haiku (cycle 24 step 8), by hand only: costs money, needs the key, and a book file
 * ({@code book_id\ttitle\tauthor[\tline]}, for example the step 1 list from {@code scripts/aisearch/repo_list_books.py}).
 * Every book is held by the plan's one library. Prints the events in short and the record, never the key.
 * <pre>
 * CHAEKCHECK_AISEARCH_LIVE=1 CHAEKCHECK_AISEARCH_LIVE_BOOKS=books.tsv ./gradlew test --tests '*AiSearchFlowLiveTests'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CHAEKCHECK_AISEARCH_LIVE", matches = "1")
@SpringBootTest
@Transactional
class AiSearchFlowLiveTests {

	@Autowired
	JdbcClient jdbc;

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

	@Test
	void threeQuestionsAgainstTheRealModel() throws Exception {
		List<ListedBook> listed = new ArrayList<>();
		List<Book> stored = new ArrayList<>();
		List<String> rows = Files.readAllLines(Path.of(System.getenv("CHAEKCHECK_AISEARCH_LIVE_BOOKS")),
				StandardCharsets.UTF_8);
		for (String row : rows.subList(1, rows.size())) {
			String[] cells = row.split("\t", -1);
			long id = Long.parseLong(cells[0]);
			String author = cells[2].isBlank() ? null : cells[2];
			listed.add(new ListedBook(id, cells[1], author, null, new long[] { 1 }, List.of("test")));
			stored.add(new Book(id, cells[1], author, new long[] { 1 }));
		}
		texts.replace(stored, List.of());
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
		AnthropicLlmClient llm = new AnthropicLlmClient(
				new AnthropicProperties(System.getenv("CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY"), null, null, null, "low"),
				HttpClient.newHttpClient(), json);
		RecordingLog log = new RecordingLog();
		AiSearchFlow flow = new AiSearchFlow(llm, candidates, budget, books, new SearchBooks(jdbc), ids -> java.util.Map.of(), ids -> java.util.Map.of(),
				ids -> java.util.Map.of(),
				com.chaekcheck.aisearch.internal.vector.QuestionVectors.OFF,
				com.chaekcheck.aisearch.internal.firstcall.MlFirstCall.off(), AiSearchFlowTests.years(java.util.Map.of()), log,
				properties, json);
		UsageRequest request = new UsageRequest(Instant.now(), null, "login_off", null, null, true);

		for (String question : List.of("잠들기 전에 조금씩 읽을 과학책", "채식주의자", "오늘 점심 뭐 먹지")) {
			Sink sink = new Sink();
			flow.run(new AiSearchFlow.Input(question, 1, List.of(), Set.of(1L), request, System.nanoTime()), sink);
			String head = String.join("", sink.of(Text.class).stream().map(Text::text).toList());
			System.out.printf("live flow: %d events, head %d chars, cards %d, done %s, record %s%n", sink.events.size(),
					head.length(), sink.of(Card.class).size(), sink.last(Done.class).result(),
					log.records.getLast());
			assertThat(head).doesNotContain("후보");
			assertThat(sink.names().getLast()).isEqualTo("done");
		}
	}

}
