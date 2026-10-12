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
	void successStoresTheLatestSixMonthsAndTheirCovers(CapturedOutput output) { // AC-12, AC-17
		fetchJob.fetch(FetchTrigger.STARTUP);

		long covers = count("SELECT count(DISTINCT cover_source) FROM picks.pick");
		Map<String, Object> run = lastRun();
		assertThat(run).containsEntry("status", "SUCCEEDED")
			.containsEntry("trigger", "STARTUP")
			.containsEntry("changed", true)
			.containsEntry("received_count", 108)
			.containsEntry("kept_count", 52)
			.containsEntry("missing_isbn_count", 0)
			.containsEntry("cover_fetched_count", (int) covers)
			.containsEntry("cover_failed_count", 0)
			.containsEntry("request_count", 1 + (int) covers);
		assertThat(run.get("failure_reason")).isNull();
		assertThat(run.get("new_category_codes")).isNull();
		assertThat(count("SELECT count(*) FROM picks.pick")).isEqualTo(52);
		assertThat(jdbc.sql("SELECT DISTINCT to_char(recom_month, 'YYYY-MM') FROM picks.pick ORDER BY 1")
			.query(String.class)
			.list()).containsExactly("2025-12", "2026-02", "2026-04", "2026-06", "2026-08", "2026-10");
		assertThat(covers).isGreaterThan(40);
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
		assertThat(count("SELECT count(*) FROM picks.pick")).isEqualTo(52);
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
		assertThat(count("SELECT count(*) FROM picks.pick")).isEqualTo(52);
		assertThat(output).contains("ERROR").contains("picks fetch (RETRY) failed: no_books");
	}

	@Test
	void anErrorAnswerIsRequestRejected(CapturedOutput output) { // AC-11
		nlk.list(new NlkResponse(401, "text/plain", ("denied " + KEY).getBytes()));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(lastRun()).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", "request_rejected")
			.containsEntry("failure_detail", "HTTP 401");
		assertThat(count("SELECT count(*) FROM picks.pick")).isZero();
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
		assertThat(count("SELECT count(*) FROM picks.pick WHERE dr_code = '999'")).isEqualTo(1);
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
		assertThat(count("SELECT count(*) FROM picks.pick")).isEqualTo(52);
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
				SELECT count(DISTINCT cover_source) FROM picks.pick WHERE recom_no = ANY(CAST(:nos AS text[]))""")
			.param("nos", recomNos(items.subList(30, 52)))
			.query(Long.class)
			.single();
		assertThat(nlk.requests()).hasSize(1 + (int) added);
		assertThat(lastRun()).containsEntry("changed", true).containsEntry("cover_fetched_count", (int) added);
		assertThat(count("SELECT count(*) FROM picks.cover")).isEqualTo(count(
				"SELECT count(DISTINCT cover_source) FROM picks.pick")).isLessThan(first + added);
		assertThat(count("""
				SELECT count(*) FROM picks.cover c
				WHERE NOT EXISTS (SELECT 1 FROM picks.pick p WHERE p.cover_source = c.source_url)""")).isZero();
	}

	@Test
	void anAddressNotAllowedIsNeverRequested() {
		List<String> items = new ArrayList<>(NlkFixtures.items(XML).subList(0, 3));
		items.set(0, NlkFixtures.set(items.get(0), "recomfilepath", "http://evil.example/afile/x"));
		items.set(1, NlkFixtures.set(items.get(1), "recomfilepath", "http://www.nl.go.kr/other/x"));
		nlk.listXml(NlkFixtures.withItems(items));

		fetchJob.fetch(FetchTrigger.SCHEDULED);

		assertThat(count("SELECT count(*) FROM picks.pick WHERE cover_source IS NULL")).isEqualTo(2);
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
		assertThat(count("SELECT count(*) FROM picks.pick")).isEqualTo(52);
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
			.subList(0, 52)
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
