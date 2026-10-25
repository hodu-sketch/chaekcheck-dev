package com.chaekcheck.foryou.internal.build;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.catalog.BookCards;
import com.chaekcheck.catalog.BookCards.BookCard;
import com.chaekcheck.catalog.BookCards.HoldingKey;
import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.catalog.TitleAuthor;
import com.chaekcheck.catalog.TitleAuthorBook;
import com.chaekcheck.foryou.internal.ForYouProperties;
import com.chaekcheck.foryou.internal.build.BaseOrder.Entry;
import com.chaekcheck.foryou.internal.listed.ListedSource;
import com.chaekcheck.foryou.internal.listed.ListedSource.ListedRef;
import com.chaekcheck.foryou.internal.source.PopularSource;
import com.chaekcheck.foryou.internal.source.SourceRead;
import com.chaekcheck.foryou.internal.source.SourceRow;
import com.chaekcheck.foryou.internal.store.ForYouStore;

/**
 * The daily build (flow A): read the sources, match their rows to our books, leave out a site or feed that gave no
 * row or matched less than {@code min-match-rate}, join editions, score, order the base list and store it. A build
 * with every popular source failing still succeeds with the listed books (R2-5). An exception fails the build and the
 * day before's list stays.
 */
public class ForYouBuild {

	private static final Logger log = LoggerFactory.getLogger(ForYouBuild.class);

	private final List<PopularSource> sources;

	private final BookCards bookCards;

	private final BookLookup bookLookup;

	private final ListedSource listedSource;

	private final ForYouStore store;

	private final ForYouProperties properties;

	private final Runnable afterSuccess;

	private final Clock clock;

	public ForYouBuild(List<PopularSource> sources, BookCards bookCards, BookLookup bookLookup,
			ListedSource listedSource, ForYouStore store, ForYouProperties properties, Runnable afterSuccess,
			Clock clock) {
		this.sources = List.copyOf(sources);
		this.bookCards = bookCards;
		this.bookLookup = bookLookup;
		this.listedSource = listedSource;
		this.store = store;
		this.properties = properties;
		this.afterSuccess = afterSuccess;
		this.clock = clock;
	}

	/** At start: fail what a crash left running, then build when today has no succeeded build. */
	public void runAtStart() {
		int failed = store.failRunning(clock.instant());
		if (failed > 0) {
			log.warn("foryou build: {} run(s) left running were made failed", failed);
		}
		if (store.succeededOn(today())) {
			log.info("foryou build: today's list is already built");
			return;
		}
		build();
	}

	public synchronized void build() {
		LocalDate today = today();
		long runId = store.start(today, clock.instant());
		StringJoiner note = new StringJoiner("\n");
		Map<Long, BookCard> cards = new HashMap<>();
		Map<Long, ScoreParts> parts;
		List<Entry> entries;
		try {
			List<MatchedRow> all = new ArrayList<>();
			List<MatchedRow> kept = new ArrayList<>();
			for (PopularSource source : sources) {
				for (SourceRead read : source.read()) {
					List<MatchedRow> rows = match(read);
					all.addAll(rows);
					String verdict = verdict(read, rows);
					note.add(verdict);
					if (verdict.endsWith(" kept")) {
						kept.addAll(rows);
					}
				}
			}
			List<ListedRef> listed = listedSource.listed();
			Set<Long> ids = new HashSet<>();
			kept.stream().filter(row -> row.bookId() != null).forEach(row -> ids.add(row.bookId()));
			listed.forEach(ref -> ids.add(ref.bookId()));
			Map<Long, Long> workKeys = bookCards.workKeys(ids);
			bookCards.cards(new HashSet<>(workKeys.values())).forEach(card -> cards.put(card.bookId(), card));
			Map<Long, Integer> years = new HashMap<>();
			cards.forEach((id, card) -> {
				if (card.publishedYear() != null) {
					years.put(id, card.publishedYear());
				}
			});
			parts = Scoring.score(kept, workKeys, years, today.getYear(), properties.score());
			Map<Long, Double> totals = new LinkedHashMap<>();
			parts.forEach((work, part) -> {
				if (cards.containsKey(work)) {
					totals.put(work, part.total());
				}
			});
			Map<Long, Integer> listedWorks = new HashMap<>();
			for (ListedRef ref : listed) {
				Long work = workKeys.get(ref.bookId());
				if (work != null && cards.containsKey(work)) {
					listedWorks.put(work, cards.get(work).libraryIds().length);
				}
			}
			entries = BaseOrder.order(totals, listedWorks, properties.build().baseSize());
			Map<Long, Double> stored = new HashMap<>();
			totals.forEach((work, total) -> {
				if (total > 0) {
					stored.put(work, total);
				}
			});
			note.add("base=" + entries.size() + " popular=" + entries.stream().filter(Entry::fromPopular).count()
					+ " listed=" + listedWorks.size() + " scored=" + stored.size());
			store.succeed(runId, all, stored, entries, note.toString(), clock.instant());
		}
		catch (RuntimeException ex) {
			note.add("failed: " + ex);
			log.error("foryou build {} failed, the list before stays", runId, ex);
			store.fail(runId, note.toString(), clock.instant());
			return;
		}
		// the build is stored: what follows only logs when it fails, it never marks the build failed
		try {
			log.info("foryou build {} succeeded: {}", runId, note.toString().replace('\n', ';'));
			logTop(entries, parts, cards);
			afterSuccess.run();
			int deleted = store.deleteBefore(today.minusDays(properties.build().keep().toDays()));
			if (deleted > 0) {
				log.info("foryou build: {} old run(s) deleted", deleted);
			}
		}
		catch (RuntimeException ex) {
			log.warn("foryou build {} succeeded, but the steps after it failed: {}", runId, ex.toString());
		}
	}

	/** {@code kyobo_elib:31 rows=100 matched=99 kept}, or {@code … left_out (…)}. */
	private String verdict(SourceRead read, List<MatchedRow> rows) {
		long matched = rows.stream().filter(row -> row.bookId() != null).count();
		String counts = read.unit() + " rows=" + rows.size() + " matched=" + matched;
		if (read.failure() != null) {
			return counts + " left_out (" + read.failure() + ")";
		}
		if (rows.isEmpty()) {
			return counts + " left_out (no rows)";
		}
		if (matched < properties.build().minMatchRate() * rows.size()) {
			return counts + " left_out (match rate)";
		}
		return counts + " kept";
	}

	/**
	 * Our book of each row: the site's holding (the library's own row, as the pretest matched 2,590 / 2,600), then the
	 * ISBN-13, then title and first author (the most held book when several match) [안].
	 */
	List<MatchedRow> match(SourceRead read) {
		List<SourceRow> rows = clean(read.rows());
		Map<HoldingKey, Long> byHolding = read.siteLibraryId() == null ? Map.of()
				: bookCards.byHoldingKeys(rows.stream()
					.filter(row -> row.itemKey() != null)
					.map(row -> new HoldingKey(read.siteLibraryId(), row.itemKey()))
					.toList());
		Map<String, Long> byIsbn = new HashMap<>();
		Collection<String> isbns = rows.stream().map(SourceRow::isbn13).filter(isbn -> isbn != null).toList();
		for (IsbnBook book : bookLookup.byIsbn13(isbns)) {
			byIsbn.put(book.isbn13(), book.bookId());
		}
		Map<TitleAuthor, TitleAuthorBook> byTitle = new HashMap<>();
		List<TitleAuthor> titles = new ArrayList<>();
		for (SourceRow row : rows) {
			if (found(read, row, byHolding, byIsbn) == null && row.title() != null && row.author() != null) {
				titles.add(new TitleAuthor(row.title(), row.author()));
			}
		}
		for (TitleAuthorBook book : bookLookup.byTitleAndAuthor(titles)) {
			byTitle.merge(book.query(), book, (a, b) -> a.libraryIds().length >= b.libraryIds().length ? a : b);
		}
		List<MatchedRow> matched = new ArrayList<>(rows.size());
		for (SourceRow row : rows) {
			Long bookId = found(read, row, byHolding, byIsbn);
			if (bookId == null && row.title() != null && row.author() != null) {
				TitleAuthorBook book = byTitle.get(new TitleAuthor(row.title(), row.author()));
				bookId = book == null ? null : book.bookId();
			}
			matched.add(new MatchedRow(read.source(), read.siteLibraryId(), read.period(), row, bookId));
		}
		return matched;
	}

	/**
	 * A row the tables cannot keep (no title) is dropped; a rank below 1 and negative counts are made 1 and 0, so one odd
	 * row never fails the day's build.
	 */
	private static List<SourceRow> clean(List<SourceRow> rows) {
		List<SourceRow> clean = new ArrayList<>(rows.size());
		for (SourceRow row : rows) {
			if (row.title() == null || row.title().isBlank()) {
				continue;
			}
			clean.add(new SourceRow(Math.max(1, row.rank()), row.title(), row.author(), row.publisher(), row.isbn13(),
					row.itemKey(), row.loansNow() == null ? null : Math.max(0, row.loansNow()),
					row.reservations() == null ? null : Math.max(0, row.reservations())));
		}
		return clean;
	}

	private static Long found(SourceRead read, SourceRow row, Map<HoldingKey, Long> byHolding, Map<String, Long> byIsbn) {
		if (read.siteLibraryId() != null && row.itemKey() != null) {
			Long bookId = byHolding.get(new HoldingKey(read.siteLibraryId(), row.itemKey()));
			if (bookId != null) {
				return bookId;
			}
		}
		return row.isbn13() == null ? null : byIsbn.get(row.isbn13());
	}

	/** The list with its materials, once a day, for weight tuning (checkpoint C, R2-3a). */
	private static void logTop(List<Entry> entries, Map<Long, ScoreParts> parts, Map<Long, BookCard> cards) {
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			ScoreParts part = parts.get(entry.bookId());
			BookCard card = cards.get(entry.bookId());
			log.info("foryou base position={} book={} popular={} year={} total={} demand={} recent={} boost={}"
					+ " sales={} top200={} klwave={} title=\"{}\"", i + 1, entry.bookId(), entry.fromPopular(),
					card == null ? null : card.publishedYear(), part == null ? 0 : round(part.total()),
					part == null ? 0 : part.demand(), part == null ? 0 : round(part.recent()),
					part == null ? 0 : part.yearBoost(), part == null ? 0 : round(part.kpipaRank()),
					part == null ? 0 : round(part.kpipa200()), part == null ? 0 : round(part.klwave()),
					card == null ? "" : card.title());
		}
	}

	private static double round(double value) {
		return Math.round(value * 10) / 10.0;
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(properties.build().zone()));
	}

}
