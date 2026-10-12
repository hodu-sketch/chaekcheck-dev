package com.chaekcheck.catalog.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.catalog.TitleAuthor;
import com.chaekcheck.catalog.TitleAuthorBook;
import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.Contributors;
import com.chaekcheck.catalog.normalize.Isbn;

/**
 * One-off measurement, local development only (plan cycle 14 step 1): the latest six recommendation months of the
 * plan's saved CSV (no API call) matched against the local service {@code db} named in {@code ../.env} (DB_PORT,
 * DB_NAME, DB_USERNAME, DB_PASSWORD) with the real {@link JdbcBookLookup}. Never runs in the build: only with
 * {@code CHAEKCHECK_PICKS_MATCH_CSV=<path to saseo-all-2026-10-12.csv>}:
 *
 * <pre>
 * CHAEKCHECK_PICKS_MATCH_CSV=../../chaekcheck-data/librarian-picks/saseo-all-2026-10-12.csv ./gradlew test --tests '*LocalPicksMatchRunner' --rerun
 * </pre>
 *
 * Writes {@code build/picks-match.txt}: times, matched counts, books with several candidates, every book's result and
 * the {@code EXPLAIN ANALYZE} of the title-and-author query. Read only.
 */
@EnabledIfEnvironmentVariable(named = "CHAEKCHECK_PICKS_MATCH_CSV", matches = ".+")
class LocalPicksMatchRunner {

	@Test
	void measure() throws IOException {
		Map<String, String> env = dotEnv(Path.of("..", ".env"));
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				"jdbc:postgresql://localhost:" + env.getOrDefault("DB_PORT", "5432") + "/" + env.get("DB_NAME"),
				env.get("DB_USERNAME"), env.get("DB_PASSWORD"));
		JdbcClient jdbc = JdbcClient.create(dataSource);
		JdbcBookLookup lookup = new JdbcBookLookup(jdbc);
		List<Map<String, String>> picks = latestSixMonths(csv(Path.of(System.getenv("CHAEKCHECK_PICKS_MATCH_CSV"))));
		StringBuilder out = new StringBuilder();
		out.append("books ").append(picks.size()).append('\n');

		// warm-up, then the measured calls
		lookup.byIsbn13(List.of("9791124591055"));
		List<String> isbns = picks.stream().map(p -> Isbn.toIsbn13(p.get("recomisbn"))).filter(Objects::nonNull).toList();
		long t0 = System.nanoTime();
		List<IsbnBook> byIsbn = lookup.byIsbn13(isbns);
		long isbnMs = (System.nanoTime() - t0) / 1_000_000;
		Map<String, List<IsbnBook>> isbnHits = byIsbn.stream().collect(Collectors.groupingBy(IsbnBook::isbn13));

		List<TitleAuthor> all = picks.stream().map(p -> new TitleAuthor(p.get("recomtitle"), p.get("recomauthor"))).toList();
		List<TitleAuthor> rest = new ArrayList<>();
		for (int i = 0; i < picks.size(); i++) {
			String isbn = Isbn.toIsbn13(picks.get(i).get("recomisbn"));
			if (isbn == null || !isbnHits.containsKey(isbn)) {
				rest.add(all.get(i));
			}
		}
		lookup.byTitleAndAuthor(List.of(new TitleAuthor("가", "가")));
		t0 = System.nanoTime();
		List<TitleAuthorBook> restHits = lookup.byTitleAndAuthor(rest);
		long restMs = (System.nanoTime() - t0) / 1_000_000;
		t0 = System.nanoTime();
		List<TitleAuthorBook> allHits = lookup.byTitleAndAuthor(all);
		long allMs = (System.nanoTime() - t0) / 1_000_000;
		Map<TitleAuthor, List<TitleAuthorBook>> titleHits = allHits.stream()
			.collect(Collectors.groupingBy(TitleAuthorBook::query));

		int first = 0;
		int either = 0;
		int titleOnly = 0;
		int several = 0;
		StringBuilder rows = new StringBuilder();
		for (int i = 0; i < picks.size(); i++) {
			Map<String, String> p = picks.get(i);
			String isbn = Isbn.toIsbn13(p.get("recomisbn"));
			List<IsbnBook> a = isbn == null ? List.of() : isbnHits.getOrDefault(isbn, List.of());
			List<TitleAuthorBook> b = titleHits.getOrDefault(all.get(i), List.of());
			if (!a.isEmpty()) {
				first++;
			}
			if (!a.isEmpty() || !b.isEmpty()) {
				either++;
			}
			if (a.isEmpty() && !b.isEmpty()) {
				titleOnly++;
			}
			if (a.size() > 1 || (a.isEmpty() && b.size() > 1)) {
				several++;
			}
			rows.append(String.join(" | ", p.get("recomYear") + "." + p.get("recomMonth"), p.get("drCode"),
					p.get("recomtitle"), p.get("recomauthor"), "key=" + CatalogText.pickTitleKey(p.get("recomtitle")),
					"author=" + Contributors.firstNameExact(p.get("recomauthor")), "isbn=" + isbn,
					"byIsbn=" + a.stream().map(x -> x.bookId() + "(" + x.libraryIds().length + ")").toList(),
					"byTitle=" + b.stream().map(x -> x.bookId() + "(" + x.libraryIds().length + ")").toList()))
				.append('\n');
		}
		out.append("byIsbn13 once: ").append(isbnMs).append("ms, ").append(isbns.size()).append(" ISBNs\n");
		out.append("byTitleAndAuthor rest: ").append(restMs).append("ms, ").append(rest.size()).append(" queries\n");
		out.append("byTitleAndAuthor all: ").append(allMs).append("ms, ").append(all.size()).append(" queries\n");
		out.append("matched by ISBN: ").append(first).append(", by ISBN or title+author: ").append(either)
			.append(", by title+author only: ").append(titleOnly).append(", several candidates: ").append(several)
			.append('\n');
		out.append("rest hits: ").append(restHits.size()).append('\n');
		out.append('\n').append(rows).append('\n');
		String[] names = all.stream()
			.map(q -> Contributors.firstNameExact(q.author()))
			.filter(Objects::nonNull)
			.distinct()
			.toArray(String[]::new);
		jdbc.sql("""
				EXPLAIN (ANALYZE, BUFFERS)
				SELECT DISTINCT c.name_exact, b.id, b.title, b.library_ids
				FROM catalog.book_contributor c JOIN catalog.book b ON b.id = c.book_id
				WHERE c.name_exact COLLATE "C" = ANY(CAST(:names AS text[]))""")
			.param("names", names)
			.query(String.class)
			.list()
			.forEach(line -> out.append(line).append('\n'));
		Files.writeString(Path.of("build", "picks-match.txt"), out, StandardCharsets.UTF_8);
	}

	private static List<Map<String, String>> latestSixMonths(List<Map<String, String>> rows) {
		Set<YearMonth> months = new TreeSet<>(Comparator.reverseOrder());
		rows.forEach(r -> months.add(month(r)));
		Set<YearMonth> kept = Set.copyOf(months.stream().limit(6).toList());
		return rows.stream().filter(r -> kept.contains(month(r))).toList();
	}

	private static YearMonth month(Map<String, String> row) {
		return YearMonth.of(Integer.parseInt(row.get("recomYear").strip()), Integer.parseInt(row.get("recomMonth").strip()));
	}

	/** A UTF-8 (BOM) CSV with a header line; quoted fields may hold commas, quotes and line breaks. */
	private static List<Map<String, String>> csv(Path path) throws IOException {
		String text = Files.readString(path, StandardCharsets.UTF_8);
		if (text.startsWith("﻿")) {
			text = text.substring(1);
		}
		List<List<String>> records = new ArrayList<>();
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
					field.append('"');
					i++;
				}
				else if (c == '"') {
					quoted = false;
				}
				else {
					field.append(c);
				}
			}
			else if (c == '"') {
				quoted = true;
			}
			else if (c == ',') {
				fields.add(field.toString());
				field.setLength(0);
			}
			else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
					i++;
				}
				fields.add(field.toString());
				field.setLength(0);
				records.add(fields);
				fields = new ArrayList<>();
			}
			else {
				field.append(c);
			}
		}
		if (field.length() > 0 || !fields.isEmpty()) {
			fields.add(field.toString());
			records.add(fields);
		}
		List<String> header = records.getFirst();
		List<Map<String, String>> rows = new ArrayList<>();
		for (List<String> record : records.subList(1, records.size())) {
			if (record.size() < header.size() || record.get(header.indexOf("recomtitle")).isBlank()) {
				continue;
			}
			Map<String, String> row = new LinkedHashMap<>();
			for (int i = 0; i < header.size(); i++) {
				row.put(header.get(i), record.get(i));
			}
			rows.add(row);
		}
		return rows;
	}

	private static Map<String, String> dotEnv(Path path) throws IOException {
		Map<String, String> values = new HashMap<>();
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			int eq = line.indexOf('=');
			if (!line.isBlank() && !line.startsWith("#") && eq > 0) {
				values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
			}
		}
		return values;
	}

}
