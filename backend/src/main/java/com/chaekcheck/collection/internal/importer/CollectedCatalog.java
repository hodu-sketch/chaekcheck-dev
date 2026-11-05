package com.chaekcheck.collection.internal.importer;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.PublishedDates;
import com.chaekcheck.collection.internal.SiteHosts;
import com.chaekcheck.library.LibraryRef;

/**
 * Reads the collection DB (a copy such as {@code ebook-catalog-2026-10-05-0745.sqlite}, or the live file while the
 * crawler writes in WAL mode) read-only. Everything is read in one transaction, so the two passes of an import
 * ({@link #scan}) see the same rows. Only plain JDBC is used: the driver ({@code sqlite-jdbc}) is on the import
 * classpath only.
 * <p>
 * The rows' {@code author_raw} and {@code publisher_raw} are the collected text, except for the libraries of
 * {@code author_publisher_swapped.tsv}, whose two values are swapped here (cycle 19, ADR-0115): their holdings keep
 * the swapped, right values.
 */
final class CollectedCatalog implements AutoCloseable {

	/** Collection ids from here on are libraries found only in the collected list and added later (the seed rule, ADR-0052). */
	static final long FIRST_COLLECTED_ONLY_ID = 100_001;

	/**
	 * Skip reason of the rows of a library added later left out by {@code exclude-added-later-libraries} (cycle 12;
	 * named {@code exclude-candidate-libraries} and {@code library_excluded_candidate} until cycle 18).
	 */
	static final String EXCLUDED_ADDED_LATER = "library_excluded_added_later";

	/** Dates before this are placeholders in the libraries named by {@code placeholder-date-libraries} (ADR-0054). */
	static final LocalDate PLACEHOLDER_DATES_BEFORE = LocalDate.of(1950, 1, 1);

	/** swap_before days are Korean days. */
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	/**
	 * A collection library whose rows are imported under its service id.
	 * @param host lower-case host of the base URL; detail URLs must be on it
	 * @param origin scheme, host and port of the service base URL; detail URLs are moved onto it
	 * @param authorPublisherSwapped its author and publisher columns were collected the other way round
	 * ({@code author_publisher_swapped.tsv}, cycle 19 ADR-0115): {@link #scan} reads them swapped
	 */
	record Library(long serviceId, long collectionId, String platformType, String host, String origin,
			Instant collectedAt, boolean placeholderDates, boolean authorPublisherSwapped) {

		Library(long serviceId, long collectionId, String platformType, String host, String origin,
				Instant collectedAt, boolean placeholderDates) {
			this(serviceId, collectionId, platformType, host, origin, collectedAt, placeholderDates, false);
		}

		boolean addedLater() {
			return collectionId >= FIRST_COLLECTED_ONLY_ID;
		}

	}

	/**
	 * The collection libraries: the ones to import, and the skip reason of the others' rows.
	 * @param imported by collection id
	 * @param skipped collection id → skip reason of its rows
	 * @param rules the rules files, for the rows' supplier code keys
	 */
	record Libraries(Map<Long, Library> imported, Map<Long, String> skipped, ImportRules rules) {

		Libraries(Map<Long, Library> imported, Map<Long, String> skipped) {
			this(imported, skipped, ImportRules.NONE);
		}

	}

	/**
	 * Screen kinds whose rows have a supplier code for step ② (cycle 19 R2-3, ADR-0114): the column holding the
	 * supplier's product code. Rule: docs/maintenance/rules-index.md "공급사 코드 칸".
	 */
	static final Map<String, String> SUPPLIER_CODE_COLUMN = Map.of("BOOKCUBE_FX", "alt_code", "KYOBO_V1", "item_key",
			"KYOBO_SUBSCRIPTION_V1", "item_key", "YES24_V1", "item_key", "YES24_V2", "item_key", "YES24_V3", "item_key");

	/**
	 * One holding row that passed the filters.
	 * @param publishedOn the parsed date; null when missing, unreadable, in the future or a placeholder
	 * @param supplierCodeKey step ②'s (supplier family, product code) key ({@link #supplierCodeKey}); null without one
	 */
	record Row(Library library, String itemKey, String detailUrl, String contentType, String title, String authorRaw,
			String publisher, String publishedRaw, LocalDate publishedOn, String supplier, String altCode,
			Instant firstSeenAt, String supplierCodeKey) {

		/** A row without a supplier code key (tests). */
		Row(Library library, String itemKey, String detailUrl, String contentType, String title, String authorRaw,
				String publisher, String publishedRaw, LocalDate publishedOn, String supplier, String altCode,
				Instant firstSeenAt) {
			this(library, itemKey, detailUrl, contentType, title, authorRaw, publisher, publishedRaw, publishedOn,
					supplier, altCode, firstSeenAt, null);
		}

	}

	/**
	 * Step ②'s supplier code key (cycle 19 R2-3, ADR-0114): the supplier's family ({@link ImportRules#family}), the
	 * product code of the screen kind ({@link #SUPPLIER_CODE_COLUMN}, spaces taken off), the row's step ④
	 * comparison title ({@link SameTextKey#comparisonTitle}) and its publisher ({@link CatalogText#publisherNorm}): one
	 * code joins only rows with the same title and publisher. Title and publisher were added after the cycle 19 dry
	 * runs (user choices 2026-10-16): some libraries put one code on different books (library 703's 우리전자책 rows;
	 * same-titled books of other publishers), while rows whose only error is the author field still join. Null when
	 * the kind has no code, or the code, the supplier, the comparison title or the publisher is empty or a placeholder
	 * ({@link SameTextKey#PLACEHOLDER_PUBLISHERS}, third dry run: 「귀가」 of two authors under 기타): such rows are not
	 * joined by code. Computed once here, so the three groupings count the same keys.
	 */
	static String supplierCodeKey(String platformType, String itemKey, String altCode, String family, String title,
			String publisher) {
		String column = SUPPLIER_CODE_COLUMN.get(platformType);
		if (column == null || family == null || title == null) {
			return null;
		}
		String code = column.equals("alt_code") ? altCode : itemKey;
		code = code == null ? "" : code.strip();
		String comparison = SameTextKey.RULES.comparisonTitle(title);
		String publisherNorm = CatalogText.publisherNorm(publisher);
		boolean noPublisher = publisherNorm == null || SameTextKey.PLACEHOLDER_PUBLISHERS.contains(publisherNorm);
		return code.isEmpty() || comparison.isEmpty() || noPublisher ? null
				: family + BookGrouper.SEP + code + BookGrouper.SEP + comparison + BookGrouper.SEP + publisherNorm;
	}

	private final Connection connection;

	private CollectedCatalog(Connection connection) {
		this.connection = connection;
	}

	static CollectedCatalog open(Path sqlite) throws SQLException {
		if (!Files.isRegularFile(sqlite)) {
			throw new IllegalArgumentException("Collection DB not found: " + sqlite);
		}
		Properties props = new Properties();
		// SQLITE_OPEN_READONLY: the crawler owns the file
		props.setProperty("open_mode", "1");
		Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath(), props);
		connection.setAutoCommit(false);
		return new CollectedCatalog(connection);
	}

	/**
	 * Maps every collection library through the links (ADR-0052, plan handoff C10): no link or no enabled service
	 * library → {@code library_not_in_service}; a base URL on another site than the service library's →
	 * {@code library_base_url_mismatch} (a safety check, {@link #sameSite}). {@code collected_at} is the collected
	 * {@code last_full_success_at} [안]; a library without one uses its newest {@code last_seen_at}, and one without
	 * rows at all is not imported.
	 * With {@code excludeAddedLater} (a dry run of cycle 12), a library added later that would be imported is left
	 * out instead: {@value #EXCLUDED_ADDED_LATER}, not counted in {@code otherLibraries}. Libraries skipped for the
	 * reasons above keep those reasons, so only those libraries' imported rows differ from a run without it.
	 * A library read swapped ({@link ImportRules#swapped}) whose copy was collected on or after its
	 * {@code swap_before} day stops the import: the crawler fixed on that day reads it right, so swapping would turn
	 * it wrong again (cycle 19, ADR-0115).
	 * @param placeholderDateLibraries collection ids whose dates before 1950 are placeholders
	 * @param excludeAddedLater leave out the libraries added later ({@link Library#addedLater()})
	 * @param rules the import's rules files
	 */
	Libraries libraries(Map<Long, Long> links, List<LibraryRef> serviceLibraries, Set<Long> placeholderDateLibraries,
			boolean excludeAddedLater, ImportRules rules, ImportReport report) throws SQLException {
		Map<Long, LibraryRef> service = new HashMap<>();
		serviceLibraries.forEach(ref -> service.put(ref.id(), ref));
		Map<Long, Library> imported = new HashMap<>();
		Map<Long, String> skipped = new HashMap<>();
		String sql = """
				SELECT l.id, l.base_url, l.last_full_success_at,
				  (SELECT max(h.last_seen_at) FROM holding h WHERE h.library_id = l.id) AS last_seen
				FROM library l ORDER BY l.id""";
		try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				long collectionId = rs.getLong(1);
				Long serviceId = links.get(collectionId);
				LibraryRef ref = serviceId == null ? null : service.get(serviceId);
				if (ref == null) {
					skipped.put(collectionId, "library_not_in_service");
					report.librariesNotInService++;
					continue;
				}
				if (!sameSite(ref.baseUrl(), rs.getString(2))) {
					skipped.put(collectionId, "library_base_url_mismatch");
					report.librariesBaseUrlMismatch++;
					continue;
				}
				String fullSuccess = rs.getString(3);
				String collected = fullSuccess != null ? fullSuccess : rs.getString(4);
				if (collected == null) {
					skipped.put(collectionId, "library_not_collected");
					report.librariesNotCollected++;
					continue;
				}
				Library library = new Library(ref.id(), collectionId, ref.platformType().name(), hostOf(ref.baseUrl()),
						originOf(ref.baseUrl()), instant(collected), placeholderDateLibraries.contains(collectionId),
						rules.swapped(collectionId));
				if (library.authorPublisherSwapped()) {
					LocalDate before = rules.swapBefore().get(collectionId);
					if (!library.collectedAt().isBefore(before.atStartOfDay(KOREA).toInstant())) {
						throw new IllegalStateException("collection library " + collectionId + " was collected at "
								+ library.collectedAt() + ", on or after swap_before " + before + " of "
								+ ImportRules.SWAPPED_FILE + ": before removing its row, check the copy with"
								+ " scripts/library/check_author_publisher_swap.py (docs/maintenance/3-import.md)");
					}
				}
				if (excludeAddedLater && library.addedLater()) {
					skipped.put(collectionId, EXCLUDED_ADDED_LATER);
					continue;
				}
				if (fullSuccess == null) {
					report.librariesWithoutFullSuccess++;
				}
				imported.put(collectionId, library);
				if (library.addedLater()) {
					report.otherLibraries++;
				}
				else {
					report.registeredLibraries++;
				}
			}
		}
		return new Libraries(imported, skipped, rules);
	}

	/**
	 * Reads every holding row and gives the ones that pass the filters to {@code sink}, in the file's row order.
	 * Called twice per import (ADR-0054): first to group the books, then to write the holdings.
	 * @param report counts rows and skip reasons; null on the second pass so nothing is counted twice
	 */
	void scan(Libraries libraries, LocalDate today, ImportReport report, Consumer<Row> sink) throws SQLException {
		Map<String, String> shared = new HashMap<>();
		String sql = """
				SELECT h.library_id, h.item_key, h.detail_url, h.content_type, h.content_kind, h.removed_at, h.title,
				  h.author_raw, h.publisher, h.published_raw, h.published_on, h.supplier, h.alt_code, h.first_seen_at
				FROM holding h""";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setFetchSize(10_000);
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					if (report != null) {
						report.rowsRead++;
					}
					long collectionId = rs.getLong(1);
					Library library = libraries.imported().get(collectionId);
					String reason = library == null
							? libraries.skipped().getOrDefault(collectionId, "library_not_in_service")
							: skipReason(rs, library);
					if (reason != null) {
						if (report != null) {
							report.skip(reason);
						}
						continue;
					}
					String publishedRaw = rs.getString(10) != null ? rs.getString(10) : rs.getString(11);
					LocalDate publishedOn = PublishedDates.parse(rs.getString(11) != null ? rs.getString(11)
							: publishedRaw, today);
					if (publishedOn != null && library.placeholderDates()
							&& publishedOn.isBefore(PLACEHOLDER_DATES_BEFORE)) {
						publishedOn = null;
						if (report != null) {
							report.placeholderDatesCleared++;
						}
					}
					// author and publisher swapped for the libraries of author_publisher_swapped.tsv (ADR-0115), in both passes
					String author = library.authorPublisherSwapped() ? rs.getString(9) : rs.getString(8);
					String publisher = library.authorPublisherSwapped() ? rs.getString(8) : rs.getString(9);
					if (library.authorPublisherSwapped() && report != null) {
						report.authorPublisherSwappedRows++;
					}
					String supplier = share(shared, rs.getString(12));
					String codeKey = supplierCodeKey(library.platformType(), rs.getString(2), rs.getString(13),
							libraries.rules().family(supplier), rs.getString(7), publisher);
					sink.accept(new Row(library, rs.getString(2), onOrigin(rs.getString(3), library.origin()),
							rs.getString(4), rs.getString(7),
							author, share(shared, publisher), publishedRaw, publishedOn,
							supplier, rs.getString(13), instant(rs.getString(14)), codeKey));
				}
			}
		}
	}

	/** The first filter a row fails, or null ({@link HoldingRowFilter}, shared with the library insert). */
	private static String skipReason(ResultSet rs, Library library) throws SQLException {
		return HoldingRowFilter.skipReason(rs.getString(6), rs.getString(4), rs.getString(5), rs.getString(3),
				rs.getString(7), library.host());
	}

	/** Same strings (publishers, suppliers) share one instance to keep memory small. */
	private static String share(Map<String, String> shared, String value) {
		return value == null ? null : shared.computeIfAbsent(value, v -> v);
	}

	/**
	 * Whether the collected base URL is the service library's site: same host and path, any scheme and port. The
	 * service keeps the address a site redirects to (docs/libraries/library-maintenance.md 4-7: often http to https on another
	 * port), while the collection DB keeps the listed one (found 2026-10-05: 강동대학교 북큐브 http → https).
	 */
	static boolean sameSite(String serviceBaseUrl, String collectedBaseUrl) {
		if (serviceBaseUrl == null || collectedBaseUrl == null) {
			return false;
		}
		if (serviceBaseUrl.equals(collectedBaseUrl)) {
			return true;
		}
		try {
			URI service = URI.create(serviceBaseUrl);
			URI collected = URI.create(collectedBaseUrl);
			// hosts read by hand: URI gives none for names with an underscore (cycle 18)
			String host = SiteHosts.host(serviceBaseUrl);
			return host != null && host.equals(SiteHosts.host(collectedBaseUrl))
					&& pathOf(service).equals(pathOf(collected))
					&& Objects.equals(service.getRawQuery(), collected.getRawQuery());
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static String pathOf(URI uri) {
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
	}

	/**
	 * The detail URL on the service library's origin: the collected one may be the listed http address that the site
	 * now redirects to https on another port (2026-10-05, 강동대학교 북큐브). The host is the same (checked before);
	 * path and query stay. Unchanged when the URL cannot be read.
	 */
	static String onOrigin(String detailUrl, String origin) {
		try {
			URI uri = URI.create(detailUrl.strip());
			String rest = uri.getRawPath() == null ? "" : uri.getRawPath();
			if (uri.getRawQuery() != null) {
				rest += "?" + uri.getRawQuery();
			}
			return origin == null ? detailUrl : origin + rest;
		}
		catch (IllegalArgumentException ex) {
			return detailUrl;
		}
	}

	/** {@code scheme://host[:port]} of a URL; null when it cannot be read. */
	static String originOf(String url) {
		try {
			URI uri = URI.create(url);
			return uri.getScheme() == null || uri.getRawAuthority() == null ? null
					: uri.getScheme() + "://" + uri.getRawAuthority();
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/**
	 * Host name in lower case, the port left out (the host throttle works on host names, ADR-0022). Names with an
	 * underscore are read too ({@link SiteHosts}, cycle 18).
	 */
	static String hostOf(String url) {
		return SiteHosts.host(url);
	}

	/** Host of an http(s) URL; null for any other scheme or a URL that cannot be read ({@link SiteHosts}). */
	static String httpHostOf(String url) {
		return SiteHosts.httpHost(url);
	}

	private static Instant instant(String value) {
		if (value == null) {
			return null;
		}
		return OffsetDateTime.parse(value).toInstant();
	}

	@Override
	public void close() throws SQLException {
		connection.rollback();
		connection.close();
	}

}
