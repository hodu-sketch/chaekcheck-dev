package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.picks.internal.PicksProperties;
import com.chaekcheck.picks.internal.fetch.NlkFixtures;
import com.chaekcheck.picks.internal.fetch.NlkResponse;
import com.chaekcheck.picks.internal.schedule.FetchDecision;
import com.chaekcheck.picks.internal.schedule.FetchJob;
import com.chaekcheck.picks.internal.schedule.FetchScheduler;
import com.chaekcheck.picks.internal.schedule.FetchTrigger;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * Fetches from the fake library, stored (plan cycle 14 steps 3 and 4; AC-4b, AC-11, AC-12, AC-17). The fetch is called
 * directly on the test thread, so it joins the test transaction and nothing stays in db-test. Fetching stays off in
 * this context (no start-up fetch, no daily fetch); the key is a test value. The context (with the fake library) is
 * used by this class only and closed after it, so its connections do not count against db-test's limit.
 */
@SpringBootTest(properties = "chaekcheck.picks.fetch.api-key=" + PicksFetchTests.KEY)
@Transactional
@Import(FakeNlk.class)
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext
class PicksFetchTests {

	static final String KEY = "test-secret-key-123";

	static final String XML = NlkFixtures.saseo2y();

	@Autowired
	FetchJob fetchJob;

	@Autowired
	FakeNlk nlk;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	PicksProperties properties;

	@Autowired
	FetchDecision fetchDecision;

	@Autowired
	PicksStore store;

	@BeforeEach
	void resetLibrary() {
		nlk.reset();
	}

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	@Test
	void offMeansNoSchedulerAndNoRequest() { // C3
		assertThat(context.getBeansOfType(FetchScheduler.class)).isEmpty();
		assertThat(nlk.requests()).isEmpty();
	}

	@Test
	void successStoresTheLatestTwelveMonthsWithOneRequest(CapturedOutput output) { // AC-12, AC-17
		fetchJob.fetch(FetchTrigger.STARTUP);

		long covers = count("SELECT count(DISTINCT cover_source) FROM picks.list_book");
		Map<String, Object> run = lastRun();
		assertThat(run).containsEntry("status", "SUCCEEDED")
			.containsEntry("trigger", "STARTUP")
			.containsEntry("changed", true)
			.containsEntry("received_count", 108)
			.containsEntry("kept_count", 108)
			.containsEntry("missing_isbn_count", (int) count("SELECT count(*) FROM picks.list_book WHERE list_key = 'librarian' AND isbn13 IS NULL"))
			.containsEntry("cover_fetched_count", 0)
			.containsEntry("cover_failed_count", 0)
			.containsEntry("request_count", 1);
		assertThat(run.get("failure_reason")).isNull();
		assertThat(run.get("new_category_codes")).isNull();
		assertThat(count("SELECT count(*) FROM picks.list_book")).isEqualTo(108);
		assertThat(jdbc.sql("SELECT DISTINCT to_char(recommended_month, 'YYYY-MM') FROM picks.list_book ORDER BY 1")
			.query(String.class)
			.list()).containsExactly("2024-12", "2025-02", "2025-04", "2025-06", "2025-08", "2025-10", "2025-12", "2026-02",
					"2026-04", "2026-06", "2026-08", "2026-10");
		// the cover addresses are kept (the PC names the S3 pictures after them, ADR-0163), none is requested
		assertThat(covers).isGreaterThan(90);
		assertThat(nlk.requests()).hasSize(1);
		assertThat(nlk.requests().getFirst().getPath()).isEqualTo("/NL/search/openApi/saseoApi.do");
		// AC-12: no request to the publishing distribution network
		assertThat(nlk.requests()).extracting(URI::getHost).doesNotContain("bnk.kpipa.or.kr");
		assertThat(output).doesNotContain(KEY).contains("key=***");
	}

	@Test
	void theSameListAgainChangesNothing() {
		fetchJob.fetch(FetchTrigger.SCHEDULED);
		nlk.reset();

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(lastRun()).containsEntry("status", "SUCCEEDED")
			.containsEntry("changed", false)
			.containsEntry("request_count", 1)
			.containsEntry("cover_fetched_count", 0);
		assertThat(nlk.requests()).hasSize(1);
		assertThat(count("SELECT count(*) FROM picks.list_book")).isEqualTo(108);
	}

	@Test
	void theFetchLeavesTheOtherLists() { // cycle 21 AC-10 (ADR-0122)
		jdbc.sql("INSERT INTO picks.list_field (list_key, code, name, position) VALUES ('snu100', 'korean_lit', '한국문학', 1)")
			.update();
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, toc_no, book_id, cover_source)
				VALUES ('snu100', 'korean_lit', 3, '구운몽', 3, 77, 'https://image.yes24.com/goods/3/M')""").update();

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(lastRun()).containsEntry("status", "SUCCEEDED").containsEntry("changed", true);
		assertThat(jdbc.sql("""
				SELECT title || ':' || toc_no || ':' || book_id FROM picks.list_book WHERE list_key = 'snu100'""")
			.query(String.class)
			.list()).containsExactly("구운몽:3:77");
		// the list request only: no cover is requested
		assertThat(nlk.requests()).hasSize(1);
		assertThat(count("SELECT count(*) FROM picks.list_book WHERE list_key = 'librarian'")).isEqualTo(108);
	}

	@Test
	void noBooksKeepsTheListBefore(CapturedOutput output) { // AC-11
		fetchJob.fetch(FetchTrigger.SCHEDULED);
		nlk.reset();
		nlk.listXml(NlkFixtures.withItems(List.of()));

		fetchJob.fetch(FetchTrigger.RETRY);

		assertThat(lastRun()).containsEntry("status", "FAILED")
			.containsEntry("trigger", "RETRY")
			.containsEntry("failure_reason", "no_books")
			.containsEntry("request_count", 1)
			.containsEntry("kept_count", 0);
		assertThat(lastRun().get("changed")).isNull();
		assertThat(count("SELECT count(*) FROM picks.list_book")).isEqualTo(108);
		assertThat(output).contains("ERROR").contains("picks fetch (RETRY) failed: no_books");
	}

	@Test
	void anErrorAnswerIsRequestRejected(CapturedOutput output) { // AC-11
		nlk.list(new NlkResponse(401, "text/plain", ("denied " + KEY).getBytes()));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(lastRun()).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", "request_rejected")
			.containsEntry("failure_detail", "HTTP 401");
		assertThat(count("SELECT count(*) FROM picks.list_book")).isZero();
		assertThat(output).contains("ERROR").doesNotContain(KEY);

		nlk.listXml("<?xml version=\"1.0\"?><error><error_code>010</error_code></error>");
		fetchJob.fetch(FetchTrigger.RETRY);
		assertThat(lastRun()).containsEntry("failure_reason", "request_rejected")
			.containsEntry("failure_detail", "API error 010");
	}

	@Test
	void unknownFieldCodeIsStoredAndRecorded() { // AC-4b
		List<String> items = new ArrayList<>(NlkFixtures.items(XML).subList(0, 52));
		items.set(0, NlkFixtures.set(NlkFixtures.set(items.get(0), "drCode", "999"), "drCodeName", "새분야"));
		nlk.listXml(NlkFixtures.withItems(items));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(lastRun()).containsEntry("status", "SUCCEEDED").containsEntry("new_category_codes", "999");
		assertThat(count("SELECT count(*) FROM picks.list_book WHERE field_code = '999'")).isEqualTo(1);
		// cycle 21: the new field goes after the five known ones, named by the API
		assertThat(jdbc.sql("SELECT name || ':' || position FROM picks.list_field WHERE code = '999'")
			.query(String.class)
			.single()).isEqualTo("새분야:6");
	}

	@Test
	void anAddressNotAllowedIsNeverRequested() {
		List<String> items = new ArrayList<>(NlkFixtures.items(XML).subList(0, 3));
		items.set(0, NlkFixtures.set(items.get(0), "recomfilepath", "http://evil.example/afile/x"));
		items.set(1, NlkFixtures.set(items.get(1), "recomfilepath", "http://www.nl.go.kr/other/x"));
		nlk.listXml(NlkFixtures.withItems(items));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(count("SELECT count(*) FROM picks.list_book WHERE cover_source IS NULL")).isEqualTo(2);
		assertThat(nlk.requests()).hasSize(1);
		assertThat(nlk.requests()).extracting(URI::getHost).containsOnly("nl.go.kr");
	}

	@Test
	void noKeyMeansNoRequestAndAFailedRun(CapturedOutput output) { // C5
		PicksProperties.Fetch withKey = properties.fetch();
		PicksProperties.Fetch noKey = new PicksProperties.Fetch(false, " ", withKey.cron(), withKey.zone(),
				withKey.baseUrl(), withKey.window(), withKey.endRow(), withKey.connectTimeout(), withKey.readTimeout(),
				withKey.maxBodySize(), withKey.requestInterval(), withKey.userAgent());
		FetchJob job = new FetchJob(fetchDecision, store, nlk, noKey, nlk.ticker(), Clock.systemUTC(), List.of());

		job.fetch(FetchTrigger.STARTUP);

		assertThat(nlk.requests()).isEmpty();
		assertThat(lastRun()).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", "key_missing")
			.containsEntry("request_count", 0);
		assertThat(output).contains("WARN").contains("no API key");
	}

	private Map<String, Object> lastRun() {
		return jdbc.sql("SELECT * FROM picks.fetch_run ORDER BY id DESC LIMIT 1").query().singleRow();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
