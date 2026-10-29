package com.chaekcheck.picks.internal.lists;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.catalog.TitleAuthor;
import com.chaekcheck.catalog.TitleAuthorBook;
import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.picks.CuratedListTransfer;
import com.chaekcheck.picks.internal.PicksProperties;
import com.chaekcheck.picks.internal.cache.BookChoice;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * {@link CuratedListTransfer} (handoff step 4-4). Matching a row (ADR-0124, design-delta "모듈"): ① the fixed
 * edition's ISBN, ② else its title and first author narrowed to its publisher ({@link CatalogText#publisherNorm}),
 * ③ several left: {@link BookChoice}, ④ a row without a fixed edition: its own ISBN, then its title and author. No
 * match leaves {@code bookId} null (a title-text cover, spec R2-4). Two queries per lookup kind for all rows together.
 */
@Component
class CuratedLists implements CuratedListTransfer {

	private static final Logger log = LoggerFactory.getLogger(CuratedLists.class);

	/**
	 * A Lee Dong-jin row not seen public on YouTube for longer is warned about (YouTube policy III.E.4) [확정]. Rule:
	 * docs/maintenance/rules-index.md "30일 확인"
	 */
	static final int CHECK_DAYS = 30;

	private static final List<String> LISTS = List.of(ListFiles.LEEDONGJIN, ListFiles.SNU100, ListFiles.KAIST,
			ListFiles.NOBEL);

	private final JdbcClient jdbc;

	private final PicksStore store;

	private final LibraryCatalog libraries;

	private final JsonMapper json;

	private final Clock clock;

	private final ZoneId zone;

	CuratedLists(JdbcClient jdbc, PicksStore store, LibraryCatalog libraries, JsonMapper json, Clock picksClock,
			PicksProperties properties) {
		this.jdbc = jdbc;
		this.store = store;
		this.libraries = libraries;
		this.json = json;
		this.clock = picksClock;
		this.zone = properties.fetch().zone();
	}

	@Override
	public void check(Path listsDir) {
		ListFiles.read(listsDir);
	}

	@Override
	public ListPart read(Path listsDir, BookLookup lookup) {
		ListFiles.Lists lists = ListFiles.read(listsDir);
		Map<ListFiles.Row, Long> matched = match(lists.rows(), lookup);
		List<String> warnings = new ArrayList<>();
		LocalDate today = LocalDate.now(clock.withZone(zone));
		// the covers stay files: the web server serves the lists folder's covers/ (user 2026-10-20: "이미지 말이야 자바가
		// 아니라 앞에 있어야 하는 거 아니야?"); the part only names them, so the export no longer carries the images
		Map<String, Boolean> covers = new HashMap<>();
		List<Map<String, Object>> books = new ArrayList<>();
		Map<String, int[]> counts = new LinkedHashMap<>();
		LISTS.forEach(list -> counts.put(list, new int[2]));
		for (ListFiles.Row row : lists.rows()) {
			if (row.videoId() != null
					&& (row.checkedOn() == null || row.checkedOn().plusDays(CHECK_DAYS).isBefore(today))) {
				warnings.add("leedongjin.tsv line " + row.line() + ": video " + row.videoId() + " not checked since "
						+ (row.checkedOn() == null ? "ever" : row.checkedOn()) + " (over " + CHECK_DAYS + " days)");
			}
			String cover = row.coverUrl();
			if (cover != null) {
				boolean file = covers.computeIfAbsent(cover, url -> isCoverFile(listsDir, url));
				if (!file) {
					warnings.add(row.list() + ".tsv line " + row.line() + ": no cover file for " + cover);
					cover = null;
				}
			}
			Long bookId = matched.get(row);
			int[] count = counts.get(row.list());
			count[0]++;
			if (bookId != null) {
				count[1]++;
			}
			books.add(book(row, bookId, cover));
		}
		List<Map<String, Object>> fields = lists.fields()
			.stream()
			.map(field -> Map.<String, Object>of("list", field.list(), "code", field.code(), "name", field.name(),
					"position", field.position()))
			.toList();
		Map<String, Counts> result = new LinkedHashMap<>();
		counts.forEach((list, count) -> result.put(list, new Counts(count[0], count[1], count[0] - count[1])));
		Map<String, Object> part = new LinkedHashMap<>();
		part.put("fields", fields);
		part.put("books", books);
		// kept empty so an app before this change still reads the part
		part.put("covers", List.of());
		Map<String, Object> countsJson = new LinkedHashMap<>();
		result.forEach((list, count) -> countsJson.put(list,
				Map.of("rows", count.rows(), "matched", count.matched(), "unmatched", count.unmatched())));
		part.put("counts", countsJson);
		warnings.forEach(warning -> log.warn("recommended lists: {}", warning));
		return new ListPart(json.writeValueAsString(part), Map.copyOf(result), List.copyOf(warnings));
	}

	private static Map<String, Object> book(ListFiles.Row row, Long bookId, String cover) {
		Map<String, Object> book = new LinkedHashMap<>();
		book.put("list", row.list());
		book.put("field", row.field());
		book.put("position", row.position());
		book.put("title", row.title());
		book.put("author", row.author());
		book.put("publisher", row.publisher());
		book.put("isbn13", row.isbn13());
		book.put("month", row.month() == null ? null : row.month().toString());
		book.put("yearBook", row.yearBook());
		book.put("videoId", row.videoId());
		book.put("tocNo", row.tocNo());
		book.put("pinnedTitle", row.pinned().title());
		book.put("pinnedAuthor", row.pinned().author());
		book.put("pinnedPublisher", row.pinned().publisher());
		book.put("pinnedIsbn13", row.pinned().isbn13());
		book.put("bookId", bookId);
		book.put("coverUrl", cover);
		return book;
	}

	/** {@code covers/<sha1(url)>.jpg} is there and starts like a JPEG (only its first bytes are read). */
	private static boolean isCoverFile(Path dir, String url) {
		Path file = dir.resolve("covers").resolve(CoverFiles.fileName(url));
		if (!Files.isRegularFile(file)) {
			return false;
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] head = in.readNBytes(3);
			return head.length == 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + file, ex);
		}
	}

	static String sha1(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Our book per row, or no entry: one ISBN query and one title-and-author query for every row together. */
	private Map<ListFiles.Row, Long> match(List<ListFiles.Row> rows, BookLookup lookup) {
		Map<Long, Boolean> kinds = libraries.lendingKinds();
		Set<String> isbns = new LinkedHashSet<>();
		Set<TitleAuthor> queries = new LinkedHashSet<>();
		for (ListFiles.Row row : rows) {
			ListFiles.Pinned pinned = row.pinned();
			if (!pinned.isEmpty()) {
				addIsbn(isbns, pinned.isbn13());
				if (pinned.title() != null) {
					queries.add(new TitleAuthor(pinned.title(), pinned.author()));
				}
			}
			else {
				addIsbn(isbns, row.isbn13());
				queries.add(new TitleAuthor(row.title(), row.author()));
			}
		}
		Map<String, List<BookChoice.Candidate>> byIsbn = new HashMap<>();
		for (IsbnBook book : lookup.byIsbn13(isbns)) {
			byIsbn.computeIfAbsent(book.isbn13(), isbn -> new ArrayList<>())
				.add(new BookChoice.Candidate(book.bookId(), book.title(), book.publisherNorm(), book.libraryIds()));
		}
		Map<TitleAuthor, List<TitleAuthorBook>> byTitle = new HashMap<>();
		for (TitleAuthorBook book : lookup.byTitleAndAuthor(queries)) {
			byTitle.computeIfAbsent(book.query(), query -> new ArrayList<>()).add(book);
		}
		Map<ListFiles.Row, Long> matched = new HashMap<>();
		for (ListFiles.Row row : rows) {
			ListFiles.Pinned pinned = row.pinned();
			Optional<BookChoice.Candidate> found;
			if (!pinned.isEmpty()) {
				found = choose(byIsbn.get(pinned.isbn13()), kinds);
				if (found.isEmpty() && pinned.title() != null) {
					String publisher = pinned.publisher() == null ? null : CatalogText.publisherNorm(pinned.publisher());
					List<BookChoice.Candidate> same = byTitle
						.getOrDefault(new TitleAuthor(pinned.title(), pinned.author()), List.of())
						.stream()
						.filter(book -> publisher == null || publisher.equals(book.publisherNorm()))
						.map(book -> new BookChoice.Candidate(book.bookId(), book.title(), book.publisherNorm(), book.libraryIds()))
						.toList();
					found = choose(same, kinds);
				}
			}
			else {
				found = choose(byIsbn.get(row.isbn13()), kinds);
				if (found.isEmpty()) {
					found = choose(byTitle.getOrDefault(new TitleAuthor(row.title(), row.author()), List.of())
						.stream()
						.map(book -> new BookChoice.Candidate(book.bookId(), book.title(), book.publisherNorm(), book.libraryIds()))
						.toList(), kinds);
				}
			}
			found.ifPresent(candidate -> matched.put(row, candidate.bookId()));
		}
		return matched;
	}

	private static void addIsbn(Set<String> isbns, String isbn) {
		if (isbn != null) {
			isbns.add(isbn);
		}
	}

	private static Optional<BookChoice.Candidate> choose(List<BookChoice.Candidate> candidates,
			Map<Long, Boolean> kinds) {
		return candidates == null ? Optional.empty() : BookChoice.choose(candidates, kinds);
	}

	@Override
	@Transactional
	public Replaced replace(String listPartJson) {
		Part part;
		try {
			part = json.readValue(listPartJson, Part.class);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("Not a list part: " + ex.getOriginalMessage(), ex);
		}
		if (part.fields() == null || part.books() == null) {
			throw new IllegalArgumentException("Not a list part: no fields or books");
		}
		LISTS.forEach(store::lockList);
		jdbc.sql("DELETE FROM picks.list_book WHERE list_key IN ('leedongjin', 'snu100', 'kaist', 'nobel')").update();
		jdbc.sql("DELETE FROM picks.list_field WHERE list_key IN ('leedongjin', 'snu100', 'kaist', 'nobel')").update();
		for (PartField field : part.fields()) {
			jdbc.sql("""
					INSERT INTO picks.list_field (list_key, code, name, position)
					VALUES (:list, :code, :name, :position)""")
				.param("list", field.list())
				.param("code", field.code())
				.param("name", field.name())
				.param("position", field.position())
				.update();
		}
		for (PartBook book : part.books()) {
			jdbc.sql("""
					INSERT INTO picks.list_book (list_key, field_code, position, title, author, publisher, isbn13,
					  recommended_month, year_book, video_id, toc_no, pinned_title, pinned_author, pinned_publisher,
					  pinned_isbn13, book_id, cover_source)
					VALUES (:list, :field, :position, :title, :author, :publisher, :isbn13, :month, :yearBook, :videoId,
					  :tocNo, :pinnedTitle, :pinnedAuthor, :pinnedPublisher, :pinnedIsbn13, :bookId, :cover)""")
				.param("list", book.list())
				.param("field", book.field())
				.param("position", book.position())
				.param("title", book.title())
				.param("author", book.author())
				.param("publisher", book.publisher())
				.param("isbn13", book.isbn13())
				.param("month", book.month() == null ? null : Date.valueOf(book.month() + "-01"))
				.param("yearBook", book.yearBook())
				.param("videoId", book.videoId())
				.param("tocNo", book.tocNo())
				.param("pinnedTitle", book.pinnedTitle())
				.param("pinnedAuthor", book.pinnedAuthor())
				.param("pinnedPublisher", book.pinnedPublisher())
				.param("pinnedIsbn13", book.pinnedIsbn13())
				.param("bookId", book.bookId())
				.param("cover", book.coverUrl())
				.update();
		}
		// a part from before the covers became files may still carry images: they are ignored (no stored covers since
		// cycle 26 deploy 2, ADR-0166)
		log.info("recommended lists replaced: {} fields, {} books", part.fields().size(), part.books().size());
		return new Replaced(part.fields().size(), part.books().size());
	}

	/** {@code counts} is for the import's log only. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Part(List<PartField> fields, List<PartBook> books) {
	}

	record PartField(String list, String code, String name, int position) {
	}

	record PartBook(String list, String field, int position, String title, String author, String publisher,
			String isbn13, String month, Integer yearBook, String videoId, Integer tocNo, String pinnedTitle,
			String pinnedAuthor, String pinnedPublisher, String pinnedIsbn13, Long bookId, String coverUrl) {
	}

}
