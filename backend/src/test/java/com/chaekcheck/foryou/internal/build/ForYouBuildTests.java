package com.chaekcheck.foryou.internal.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.BookCards;
import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.foryou.internal.ForYouProperties;
import com.chaekcheck.foryou.internal.ForYouProperties.Build;
import com.chaekcheck.foryou.internal.ForYouProperties.Feed;
import com.chaekcheck.foryou.internal.ForYouProperties.Sites;
import com.chaekcheck.foryou.internal.ForYouProperties.Sources;
import com.chaekcheck.foryou.internal.ForYouSnapshot;
import com.chaekcheck.foryou.internal.listed.ListedSource;
import com.chaekcheck.foryou.internal.listed.ListedSource.ListedRef;
import com.chaekcheck.foryou.internal.source.PopularSource;
import com.chaekcheck.foryou.internal.source.SourceRead;
import com.chaekcheck.foryou.internal.source.SourceRow;
import com.chaekcheck.foryou.internal.store.ForYouStore;

/**
 * The daily build against db-test with fake sources (cycle 23 step 5): matching, leaving a source out, editions,
 * listed books alone, failures, the stale running run and the 30-day clean-up.
 */
@SpringBootTest
@Transactional
class ForYouBuildTests {

	static final Instant NOW = Instant.parse("2026-10-25T18:30:00Z"); // 2026-10-26 03:30 in Seoul

	static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

	@Autowired
	BookCards bookCards;

	@Autowired
	BookLookup bookLookup;

	@Autowired
	ForYouStore store;

	@Autowired
	JdbcClient jdbc;

	final List<SourceRead> reads = new ArrayList<>();

	final List<ListedRef> listed = new ArrayList<>();

	ForYouSnapshot snapshot;

	ForYouBuild build;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM foryou.build_run").update();
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, author_display, title_norm, main_title_norm, published_on,
				  content_type, library_ids)
				VALUES (930001, '돈의 속성', '김승호', 'x', '돈의속성', DATE '2020-06-15', 'EBOOK', '{1,2}'),
				  (930002, '돈의 속성(100쇄 기념 에디션)', '김승호', 'x', '돈의속성', DATE '2020-12-01', 'EBOOK', '{1,2,3}'),
				  (930003, '세이노의 가르침', '세이노', 'x', '세이노의가르침', DATE '2023-03-02', 'EBOOK', '{4}'),
				  (930004, '추천 책', '누군가', 'x', '추천책', DATE '2001-01-01', 'EBOOK', '{1,2,3,4,5}'),
				  (930005, '오래된 책', '옛사람', 'x', '오래된책', DATE '2001-01-01', 'EBOOK', '{1}')""").update();
		jdbc.sql("""
				INSERT INTO catalog.book_contributor (book_id, name, name_norm, name_exact, role, position)
				VALUES (930001, '김승호', 'x', '김승호', 'AUTHOR', 0), (930002, '김승호', 'x', '김승호', 'AUTHOR', 0),
				  (930003, '세이노', 'x', '세이노', 'AUTHOR', 0), (930005, '옛사람', 'x', '옛사람', 'AUTHOR', 0)""").update();
		jdbc.sql("""
				INSERT INTO catalog.holding (book_id, library_id, platform, item_key, detail_url, content_type, first_seen_at)
				VALUES (930001, 31, 'KYOBO', 'a1', 'https://k/1', 'EBOOK', now()),
				  (930002, 31, 'KYOBO', 'a2', 'https://k/2', 'EBOOK', now()),
				  (930003, 31, 'KYOBO', 'a3', 'https://k/3', 'EBOOK', now()),
				  (930005, 263, 'YES24', 'y5', 'https://y/5', 'EBOOK', now())""").update();
		snapshot = new ForYouSnapshot(store);
		PopularSource source = () -> List.copyOf(reads);
		build = new ForYouBuild(List.of(source), bookCards, bookLookup, () -> List.copyOf(listed), store, properties(),
				snapshot::refresh, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void editionsJoinAndBothListsComeFirst() {
		reads.add(site("kyobo_elib", 31, row(1, "a1", 2, 0), row(2, "a2", 1, 1), row(3, "a3", 10, 0)));
		listed.add(new ListedRef(930001, List.of("kaist"), new long[] { 1, 2 }, List.of()));
		listed.add(new ListedRef(930004, List.of("snu100"), new long[] { 1, 2, 3, 4, 5 }, List.of()));

		build.build();

		assertThat(status()).isEqualTo("succeeded");
		// 「돈의 속성」 (both editions, kept as the most held one) is popular and listed; then 세이노 and the listed book
		assertThat(entries()).containsExactly("930002:true", "930003:true", "930004:false");
		assertThat(jdbc.sql("SELECT count(*) FROM foryou.popular_row WHERE book_id IS NOT NULL").query(Long.class).single())
			.isEqualTo(3);
		assertThat(jdbc.sql("SELECT book_id FROM foryou.book_score ORDER BY score DESC").query(Long.class).list())
			.containsExactly(930003L, 930002L);
		assertThat(note()).contains("kyobo_elib:31 rows=3 matched=3 kept");
		assertThat(snapshot.current()).hasValueSatisfying(last -> {
			assertThat(last.builtOn()).isEqualTo(TODAY);
			assertThat(last.entries()).hasSize(3);
		});
	}

	@Test
	void aSiteWithoutRowsOrMatchingTooLittleIsLeftOut() {
		List<SourceRow> rows = new ArrayList<>();
		for (int i = 1; i <= 100; i++) {
			rows.add(row(i, i <= 89 ? "y5" : "none" + i, 1, 0));
		}
		reads.add(new SourceRead("yes24_elib", 263L, null, rows, null));
		reads.add(site("kyobo_elib", 31));
		reads.add(new SourceRead("kyobo_elib", 32L, null, List.of(), "upstream_timeout"));
		reads.add(site("kyobo_elib", 33, row(1, "x", 1, 0)));

		build.build();

		assertThat(status()).isEqualTo("succeeded");
		assertThat(note()).contains("yes24_elib:263 rows=100 matched=89 left_out (match rate)",
				"kyobo_elib:31 rows=0 matched=0 left_out (no rows)",
				"kyobo_elib:32 rows=0 matched=0 left_out (upstream_timeout)",
				"kyobo_elib:33 rows=1 matched=0 left_out (match rate)");
		assertThat(entries()).isEmpty();
		// the rows are kept for weight tuning even when their site was left out
		assertThat(jdbc.sql("SELECT count(*) FROM foryou.popular_row").query(Long.class).single()).isEqualTo(101);
	}

	@Test
	void everyPopularSourceFailingStillBuildsTheListedBooks() {
		reads.add(new SourceRead("kyobo_elib", 31L, null, List.of(), "upstream_error"));
		listed.add(new ListedRef(930004, List.of("snu100"), new long[] { 1 }, List.of()));

		build.build();

		assertThat(status()).isEqualTo("succeeded");
		assertThat(entries()).containsExactly("930004:false");
	}

	@Test
	void anExceptionFailsTheBuildAndTheListBeforeStays() {
		listed.add(new ListedRef(930004, List.of("snu100"), new long[] { 1 }, List.of()));
		build.build();
		ForYouBuild broken = new ForYouBuild(List.of(() -> {
			throw new IllegalStateException("boom");
		}), bookCards, bookLookup, ListedSource.NONE, store, properties(), snapshot::refresh,
				Clock.fixed(NOW.plus(Duration.ofDays(1)), ZoneOffset.UTC));

		broken.build();

		assertThat(jdbc.sql("SELECT status FROM foryou.build_run ORDER BY id").query(String.class).list())
			.containsExactly("succeeded", "failed");
		assertThat(store.lastSucceeded()).hasValueSatisfying(last -> assertThat(last.builtOn()).isEqualTo(TODAY));
		snapshot.refresh();
		assertThat(snapshot.current()).hasValueSatisfying(last -> assertThat(last.entries()).hasSize(1));
	}

	@Test
	void startFailsALeftRunningRunAndBuildsOnce() {
		store.start(TODAY, NOW.minusSeconds(600));

		build.runAtStart();
		build.runAtStart();

		assertThat(jdbc.sql("SELECT status FROM foryou.build_run ORDER BY id").query(String.class).list())
			.containsExactly("failed", "succeeded");
	}

	@Test
	void cleanUpKeepsTheLastSucceededRun() {
		long old = store.start(TODAY.minusDays(40), NOW);
		store.succeed(old, List.of(), java.util.Map.of(), List.of(), "", NOW);
		long older = store.start(TODAY.minusDays(41), NOW);
		store.fail(older, "x", NOW);

		assertThat(store.deleteBefore(TODAY.minusDays(30))).isEqualTo(1);
		assertThat(jdbc.sql("SELECT id FROM foryou.build_run").query(Long.class).list()).containsExactly(old);
	}

	private String status() {
		return jdbc.sql("SELECT status FROM foryou.build_run ORDER BY id DESC LIMIT 1").query(String.class).single();
	}

	private String note() {
		return jdbc.sql("SELECT note FROM foryou.build_run ORDER BY id DESC LIMIT 1").query(String.class).single();
	}

	private List<String> entries() {
		return jdbc.sql("""
				SELECT book_id || ':' || from_popular FROM foryou.base_entry
				WHERE run_id = (SELECT max(id) FROM foryou.build_run) ORDER BY position""").query(String.class).list();
	}

	private static SourceRead site(String source, long libraryId, SourceRow... rows) {
		return new SourceRead(source, libraryId, null, List.of(rows), null);
	}

	private static SourceRow row(int rank, String itemKey, int loans, int reservations) {
		return new SourceRow(rank, "제목" + rank, null, null, null, itemKey, loans, reservations);
	}

	static ForYouProperties properties() {
		return new ForYouProperties(
				new Build(true, "0 30 3 * * *", ZoneId.of("Asia/Seoul"), Duration.ofDays(30), 40, Duration.ofMinutes(10),
						0.9),
				new Sources(new Sites(true, List.of(), 100), new Sites(true, List.of(), 100), new Feed(false),
						new Feed(false), new Feed(false)),
				ScoringTests.WEIGHTS);
	}

}
