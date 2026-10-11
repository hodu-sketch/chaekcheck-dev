package com.chaekcheck.popular;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
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
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.popular.internal.PopularCategory;
import com.chaekcheck.popular.internal.schedule.FetchJob;
import com.chaekcheck.popular.internal.schedule.FetchScheduler;
import com.chaekcheck.popular.internal.schedule.FetchTrigger;

/**
 * A month fetched from the fake site and stored (plan cycle 10 step 4; AC-11, AC-12, AC-17). The fetch is called
 * directly on the test thread, so it joins the test transaction and nothing stays in db-test. Fetching stays off in
 * this context ({@code application-test.yml}): no start-up fetch, no daily fetch.
 */
@SpringBootTest
@Transactional
@Import(FakeKpipaSite.class)
@ExtendWith(OutputCaptureExtension.class)
class PopularFetchTests {

	static final YearMonth AUG = YearMonth.of(2026, 8);

	static final YearMonth SEP = YearMonth.of(2026, 9);

	static final YearMonth OCT = YearMonth.of(2026, 10);

	@Autowired
	FetchJob fetchJob;

	@Autowired
	FakeKpipaSite site;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@BeforeEach
	void resetSite() {
		site.reset();
	}

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	@Test
	void offMeansNoSchedulerAndNoRequest() { // C3
		assertThat(context.getBeansOfType(FetchScheduler.class)).isEmpty();
		assertThat(site.requests()).isEmpty();
	}

	@Test
	void successStoresSeventeenListsOf50InSalesOrder() {
		fetchJob.fetch(SEP, FetchTrigger.SCHEDULED);

		Map<String, Object> run = run(SEP);
		assertThat(run).containsEntry("status", "SUCCEEDED")
			.containsEntry("trigger", "SCHEDULED")
			.containsEntry("request_count", 34)
			.containsEntry("book_count", 850)
			.containsEntry("missing_isbn_count", 0)
			.containsEntry("cover_fetched_count", 50)
			.containsEntry("cover_failed_count", 0);
		assertThat(run.get("failure_reason")).isNull();
		assertThat(jdbc.sql("SELECT fetch_run_id FROM popular.ranking_month WHERE target_month = DATE '2026-09-01'")
			.query(Long.class)
			.single()).isEqualTo(run.get("id"));
		// AC-4 order with the 202609 counts
		assertThat(jdbc.sql("""
				SELECT category_code FROM popular.ranking_category WHERE target_month = DATE '2026-09-01'
				ORDER BY display_order""").query(String.class).list())
			.containsExactlyElementsOf(List.of(PopularCategory.values()).stream().map(PopularCategory::code).toList());
		assertThat(jdbc.sql("""
				SELECT sales_copies FROM popular.ranking_category
				WHERE target_month = DATE '2026-09-01' AND category_code = '00000000000000000303'""")
			.query(Long.class)
			.single()).isEqualTo(883_478L);
		assertThat(count("SELECT count(*) FROM popular.ranking_entry WHERE target_month = DATE '2026-09-01'"))
			.isEqualTo(850);
		assertThat(jdbc.sql("""
				SELECT isbn13 || ' ' || title FROM popular.ranking_entry
				WHERE target_month = DATE '2026-09-01' AND category_code = 'ALL' AND rank = 1""")
			.query(String.class)
			.single()).isEqualTo("9791199489561 세네카, 오늘을 빼앗기고 있는 당신에게");
		// AC-12: 34 requests for the ranking, then one per new cover
		assertThat(site.requests()).hasSize(34 + 50);
		assertThat(count("SELECT count(*) FROM popular.cover WHERE source_path LIKE '%s_202609_%'")).isEqualTo(49);
	}

	@Test
	void fewerThan50StoresNothingAndKeepsTheMonthBefore(CapturedOutput output) { // AC-11
		fetchJob.fetch(AUG, FetchTrigger.SCHEDULED);
		site.reset();
		site.shortList(PopularCategory.LITERATURE);

		fetchJob.fetch(SEP, FetchTrigger.SCHEDULED);

		assertThat(count("SELECT count(*) FROM popular.ranking_month WHERE target_month = DATE '2026-09-01'")).isZero();
		assertThat(count("SELECT count(*) FROM popular.ranking_entry WHERE target_month = DATE '2026-08-01'"))
			.isEqualTo(850);
		Map<String, Object> run = run(SEP);
		assertThat(run).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", "too_few_books")
			.containsEntry("failure_detail", "문학·인물: 49 books")
			.containsEntry("request_count", 4)
			.containsEntry("book_count", 0);
		assertThat(site.requests()).hasSize(4);
		assertThat(output).contains("ERROR").contains("popular fetch of 2026-09 failed: too_few_books");
	}

	@Test
	void fetchingAMonthAgainChangesThatMonthOnly() {
		fetchJob.fetch(AUG, FetchTrigger.SCHEDULED);
		fetchJob.fetch(SEP, FetchTrigger.STARTUP);
		long augRun = (Long) run(AUG).get("id");

		fetchJob.fetch(SEP, FetchTrigger.SCHEDULED);

		assertThat(jdbc.sql("SELECT fetch_run_id FROM popular.ranking_month WHERE target_month = DATE '2026-08-01'")
			.query(Long.class)
			.single()).isEqualTo(augRun);
		assertThat(jdbc.sql("""
				SELECT r.trigger FROM popular.ranking_month m JOIN popular.fetch_run r ON r.id = m.fetch_run_id
				WHERE m.target_month = DATE '2026-09-01'""").query(String.class).single()).isEqualTo("SCHEDULED");
		assertThat(count("SELECT count(*) FROM popular.ranking_entry WHERE target_month = DATE '2026-09-01'"))
			.isEqualTo(850);
		assertThat(count("SELECT count(*) FROM popular.fetch_run WHERE target_month = DATE '2026-09-01'")).isEqualTo(2);
		// the second fetch of September asked for no cover: all were stored
		assertThat(run(SEP)).containsEntry("cover_fetched_count", 0).containsEntry("cover_failed_count", 0);
	}

	@Test
	void failedCoversDoNotFailTheMonth() { // AC-17
		site.missingCover(FakeKpipaSite.coverOf("202609", "/files/isbn/2026/05/07/s_5d648b430bf7409bb219ba50aab1f49c.jpg"));
		site.missingCover(FakeKpipaSite.coverOf("202609", "/files/old/2002/01/20/s_8937460580_3.jpg"));

		fetchJob.fetch(SEP, FetchTrigger.SCHEDULED);

		assertThat(run(SEP)).containsEntry("status", "SUCCEEDED")
			.containsEntry("cover_fetched_count", 48)
			.containsEntry("cover_failed_count", 2);
		assertThat(count("SELECT count(*) FROM popular.ranking_entry WHERE target_month = DATE '2026-09-01'"))
			.isEqualTo(850);
		assertThat(jdbc.sql("SELECT content_type FROM popular.cover WHERE source_path = :path")
			.param("path", FakeKpipaSite.SHARED_COVER)
			.query(String.class)
			.single()).isEqualTo("image/jpeg");
	}

	@Test
	void coversOfTheLatestTwoMonthsOnlyStay() { // ADR-0075
		fetchJob.fetch(AUG, FetchTrigger.SCHEDULED);
		fetchJob.fetch(SEP, FetchTrigger.SCHEDULED);
		assertThat(count("SELECT count(*) FROM popular.cover WHERE source_path LIKE '%s_202608_%'")).isEqualTo(49);

		fetchJob.fetch(OCT, FetchTrigger.SCHEDULED);

		assertThat(count("SELECT count(*) FROM popular.cover WHERE source_path LIKE '%s_202608_%'")).isZero();
		assertThat(count("SELECT count(*) FROM popular.cover WHERE source_path LIKE '%s_202609_%'")).isEqualTo(49);
		assertThat(count("SELECT count(*) FROM popular.cover WHERE source_path LIKE '%s_202610_%'")).isEqualTo(49);
		assertThat(count("SELECT count(*) FROM popular.cover WHERE source_path = '" + FakeKpipaSite.SHARED_COVER + "'"))
			.isEqualTo(1);
		// the August ranking itself stays (spec R1-5)
		assertThat(count("SELECT count(*) FROM popular.ranking_entry WHERE target_month = DATE '2026-08-01'"))
			.isEqualTo(850);
	}

	@Test
	void stopDuringCoversKeepsTheRankingAndTheRun() {
		site.interruptAtCover(FakeKpipaSite.coverOf("202609", "/files/onix/2026/01/22/s_20260122163104-1367167192961414367.jpg"));

		fetchJob.fetch(SEP, FetchTrigger.SCHEDULED);
		assertThat(Thread.interrupted()).isTrue();

		assertThat(run(SEP)).containsEntry("status", "SUCCEEDED")
			.containsEntry("cover_fetched_count", 0)
			.containsEntry("cover_failed_count", 0);
		assertThat(count("SELECT count(*) FROM popular.ranking_entry WHERE target_month = DATE '2026-09-01'"))
			.isEqualTo(850);
		long stored = count("SELECT count(*) FROM popular.cover WHERE source_path LIKE '%s_202609_%'");
		assertThat(stored).isBetween(1L, 48L);

		// the next fetch gets the missing covers only
		site.reset();
		fetchJob.fetch(SEP, FetchTrigger.SCHEDULED);
		assertThat(run(SEP)).containsEntry("cover_fetched_count", (int) (49 - stored));
		assertThat(site.requests()).hasSize(34 + (int) (49 - stored));
	}

	private Map<String, Object> run(YearMonth month) {
		return jdbc.sql("""
				SELECT * FROM popular.fetch_run WHERE target_month = :month ORDER BY id DESC LIMIT 1""")
			.param("month", month.atDay(1))
			.query()
			.singleRow();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
