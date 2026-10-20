package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

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
	void successStoresTheLatestTwelveMonthsAndTheirCovers(CapturedOutput output) { // AC-12, AC-17
		fetchJob.fetch(FetchTrigger.STARTUP);

		long covers = count("SELECT count(DISTINCT cover_source) FROM picks.list_book");
		Map<String, Object> run = lastRun();
		assertThat(run).containsEntry("status", "SUCCEEDED")
			.containsEntry("trigger", "STARTUP")
			.containsEntry("changed", true)
			.containsEntry("received_count", 108)
			.containsEntry("kept_count", 108)
			.containsEntry("missing_isbn_count", (int) count("SELECT count(*) FROM picks.list_book WHERE list_key = 'librarian' AND isbn13 IS NULL"))
			.containsEntry("cover_fetched_count", (int) covers)
			.containsEntry("cover_failed_count", 0)
			.containsEntry("request_count", 1 + (int) covers);
		assertThat(run.get("failure_reason")).isNull();
		assertThat(run.get("new_category_codes")).isNull();
		assertThat(count("SELECT count(*) FROM picks.list_book")).isEqualTo(108);
		assertThat(jdbc.sql("SELECT DISTINCT to_char(recommended_month, 'YYYY-MM') FROM picks.list_book ORDER BY 1")
			.query(String.class)
			.list()).containsExactly("2024-12", "2025-02", "2025-04", "2025-06", "2025-08", "2025-10", "2025-12", "2026-02",
					"2026-04", "2026-06", "2026-08", "2026-10");
		assertThat(covers).isGreaterThan(90);
		assertThat(count("SELECT count(*) FROM picks.cover")).isEqualTo(covers);
		// one list request, then one per new cover, each at least 1s after the one before
		assertThat(nlk.requests()).hasSize(1 + (int) covers);
		assertThat(nlk.requests().getFirst().getPath()).isEqualTo("/NL/search/openApi/saseoApi.do");
		assertThat(nlk.requests().subList(1, nlk.requests().size())).allSatisfy(uri -> {
			assertThat(uri.getScheme()).isEqualTo("https");
			assertThat(uri.getHost()).isEqualTo("www.nl.go.kr");
			assertThat(uri.getPath()).startsWith("/afile/");
		});
		assertThat(nlk.ticker().sleeps()).hasSize((int) covers)
			.allSatisfy(sleep -> assertThat(sleep).isGreaterThanOrEqualTo(Duration.ofMillis(900)));
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
	void theFetchLeavesTheOtherListsAndDropsTheirOldCoverRows() { // cycle 21 AC-10 (ADR-0122)
		jdbc.sql("INSERT INTO picks.list_field (list_key, code, name, position) VALUES ('snu100', 'korean_lit', '한국문학', 1)")
			.update();
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, toc_no, book_id, cover_source)
				VALUES ('snu100', 'korean_lit', 3, '구운몽', 3, 77, 'https://image.yes24.com/goods/3/M')""").update();
		jdbc.sql("""
				INSERT INTO picks.cover (source_url, content_type, image, byte_size, fetched_at)
				VALUES ('https://image.yes24.com/goods/3/M', 'image/jpeg', '\\x01', 1, now())""").update();

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(lastRun()).containsEntry("status", "SUCCEEDED").containsEntry("changed", true);
		assertThat(jdbc.sql("""
				SELECT title || ':' || toc_no || ':' || book_id FROM picks.list_book WHERE list_key = 'snu100'""")
			.query(String.class)
			.list()).containsExactly("구운몽:3:77");
		// the other lists' covers are files since 2026-10-20 (CoverFiles): an old row of theirs is cleaned up
		assertThat(count("SELECT count(*) FROM picks.cover WHERE source_url = 'https://image.yes24.com/goods/3/M'"))
			.isZero();
		// the new librarian covers only: the other list's cover was not requested
		assertThat(nlk.requests()).extracting(URI::getHost).doesNotContain("image.yes24.com");
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
	void coversThatFailDoNotFailTheFetch() { // ADR-0091
		List<String> sources = coverSources();
		nlk.cover(sources.get(0), new NlkResponse(302, "text/html", new byte[0]));
		nlk.cover(sources.get(1), new NlkResponse(200, "text/html", "<html>".getBytes()));
		nlk.cover(sources.get(2), new NlkResponse(200, "image/jpeg", new byte[5 * 1024 * 1024 + 1]));
		nlk.cover(sources.get(3), new NlkResponse(200, "image/png", hugePngHeader()));
		nlk.cover(sources.get(4), new NlkResponse(200, "image/png", FakeNlk.PNG));
		nlk.cover(sources.get(5), new NlkResponse(200, "image/bmp", FakeNlk.BMP));
		nlk.cover(sources.get(6), new NlkResponse(404, "text/html", new byte[0]));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(lastRun()).containsEntry("status", "SUCCEEDED")
			.containsEntry("cover_failed_count", 5)
			.containsEntry("cover_fetched_count", sources.size() - 5);
		assertThat(count("SELECT count(*) FROM picks.list_book")).isEqualTo(108);
		assertThat(jdbc.sql("SELECT DISTINCT content_type FROM picks.cover").query(String.class).list())
			.containsExactly("image/jpeg");
		assertThat(count("SELECT count(*) FROM picks.cover WHERE byte_size <> octet_length(image)")).isZero();
		assertThat(width(sources.get(4))).isEqualTo(200);
		assertThat(width(sources.get(5))).isEqualTo(200);
		assertThat(width(sources.get(7))).isEqualTo(200);
	}

	@Test
	void aChangedListFetchesOnlyNewCoversAndDropsUnusedOnes() {
		List<String> items = NlkFixtures.items(XML);
		nlk.listXml(NlkFixtures.withItems(items.subList(0, 30)));
		fetchJob.fetch(FetchTrigger.SCHEDULED);
		long first = count("SELECT count(*) FROM picks.cover");
		nlk.reset();
		// the 10 newest picks go, 22 more come
		nlk.listXml(NlkFixtures.withItems(items.subList(10, 52)));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		long added = jdbc.sql("""
				SELECT count(DISTINCT cover_source) FROM picks.list_book WHERE list_key = 'librarian' AND source_no = ANY(CAST(:nos AS text[]))""")
			.param("nos", recomNos(items.subList(30, 52)))
			.query(Long.class)
			.single();
		assertThat(nlk.requests()).hasSize(1 + (int) added);
		assertThat(lastRun()).containsEntry("changed", true).containsEntry("cover_fetched_count", (int) added);
		assertThat(count("SELECT count(*) FROM picks.cover")).isEqualTo(count(
				"SELECT count(DISTINCT cover_source) FROM picks.list_book")).isLessThan(first + added);
		assertThat(count("""
				SELECT count(*) FROM picks.cover c
				WHERE NOT EXISTS (SELECT 1 FROM picks.list_book p WHERE p.cover_source = c.source_url)""")).isZero();
	}

	@Test
	void anAddressNotAllowedIsNeverRequested() {
		List<String> items = new ArrayList<>(NlkFixtures.items(XML).subList(0, 3));
		items.set(0, NlkFixtures.set(items.get(0), "recomfilepath", "http://evil.example/afile/x"));
		items.set(1, NlkFixtures.set(items.get(1), "recomfilepath", "http://www.nl.go.kr/other/x"));
		nlk.listXml(NlkFixtures.withItems(items));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(count("SELECT count(*) FROM picks.list_book WHERE cover_source IS NULL")).isEqualTo(2);
		assertThat(nlk.requests()).hasSize(2);
		assertThat(nlk.requests()).extracting(URI::getHost).containsOnly("nl.go.kr", "www.nl.go.kr");
	}

	@Test
	void stopDuringCoversKeepsTheListAndTheRun() {
		List<String> sources = coverSources();
		nlk.interruptAt(sources.get(10));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(Thread.interrupted()).isTrue();
		assertThat(lastRun()).containsEntry("status", "SUCCEEDED")
			.containsEntry("changed", true)
			.containsEntry("cover_fetched_count", 0)
			.containsEntry("request_count", 1);
		assertThat(count("SELECT count(*) FROM picks.list_book")).isEqualTo(108);
		assertThat(count("SELECT count(*) FROM picks.cover")).isBetween(0L, (long) sources.size() - 1);
	}

	@Test
	void noKeyMeansNoRequestAndAFailedRun(CapturedOutput output) { // C5
		PicksProperties.Fetch withKey = properties.fetch();
		PicksProperties.Fetch noKey = new PicksProperties.Fetch(false, " ", withKey.cron(), withKey.zone(),
				withKey.baseUrl(), withKey.window(), withKey.endRow(), withKey.connectTimeout(), withKey.readTimeout(),
				withKey.maxBodySize(), withKey.coverMaxBodySize(), withKey.requestInterval(), withKey.userAgent(),
				withKey.cover());
		FetchJob job = new FetchJob(fetchDecision, store, nlk, noKey, nlk.ticker(), Clock.systemUTC(), List.of());

		job.fetch(FetchTrigger.STARTUP);

		assertThat(nlk.requests()).isEmpty();
		assertThat(lastRun()).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", "key_missing")
			.containsEntry("request_count", 0);
		assertThat(output).contains("WARN").contains("no API key");
	}

	private List<String> coverSources() {
		return NlkFixtures.items(XML)
			.stream()
			.map(item -> item.replaceAll("(?s).*<recomfilepath>([^<]*)</recomfilepath>.*", "$1"))
			.filter(url -> !url.isBlank())
			.map(url -> url.replace("http://", "https://"))
			.distinct()
			.toList();
	}

	private static String[] recomNos(List<String> items) {
		return items.stream().map(item -> item.replaceAll("(?s).*<recomNo>([^<]*)</recomNo>.*", "$1")).toArray(String[]::new);
	}

	private int width(String source) {
		byte[] image = jdbc.sql("SELECT image FROM picks.cover WHERE source_url = :url")
			.param("url", source)
			.query(byte[].class)
			.single();
		try {
			BufferedImage read = ImageIO.read(new ByteArrayInputStream(image));
			return read.getWidth();
		}
		catch (java.io.IOException ex) {
			throw new AssertionError(ex);
		}
	}

	/** A PNG whose header says 5000 x 5000 (25M pixels, over the 16M limit); never decoded. */
	private static byte[] hugePngHeader() {
		byte[] png = FakeNlk.image("png", BufferedImage.TYPE_INT_RGB, 2, 2);
		ByteBuffer buffer = ByteBuffer.wrap(png);
		buffer.putInt(16, 5000).putInt(20, 5000);
		CRC32 crc = new CRC32();
		crc.update(png, 12, 17);
		buffer.putInt(29, (int) crc.getValue());
		return png;
	}

	private Map<String, Object> lastRun() {
		return jdbc.sql("SELECT * FROM picks.fetch_run ORDER BY id DESC LIMIT 1").query().singleRow();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
