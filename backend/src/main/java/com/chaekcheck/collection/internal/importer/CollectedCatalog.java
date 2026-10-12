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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import com.chaekcheck.catalog.normalize.PublishedDates;
import com.chaekcheck.library.LibraryRef;

/**
 * Reads the collection DB (a copy such as {@code ebook-catalog-2026-10-05-0745.sqlite}, or the live file while the
 * crawler writes in WAL mode) read-only. Everything is read in one transaction, so the two passes of an import
 * ({@link #scan}) see the same rows. Only plain JDBC is used: the driver ({@code sqlite-jdbc}) is on the import
 * classpath only.
 */
final class CollectedCatalog implements AutoCloseable {

	/** Kinds never imported [확정]. The user chose to import KIDS_BOOK, PAPER and UNKNOWN (2026-09-30). */
	static final Set<String> EXCLUDED_KINDS = Set.of("AUDIOBOOK", "VIDEO_BOOK");

	/**
	 * Site labels of sound and video files the content type does not catch [확정] (ADR-0054, Q2): Bookcube's file
	 * kinds {@code book/wma}, {@code book/vod}, {@code book/wmv} and Kyobo's {@code 동영상}.
	 */
	static final Set<String> SOUND_VIDEO_KINDS = Set.of("book/wma", "book/vod", "book/wmv", "동영상");

	/** Collection ids from here on are libraries found only in the collected list and added later (the seed rule, ADR-0052). */
	static final long FIRST_COLLECTED_ONLY_ID = 100_001;

	/** Skip reason of the rows of a candidate library left out by {@code exclude-candidate-libraries} (cycle 12). */
	static final String EXCLUDED_CANDIDATE = "library_excluded_candidate";

	/** Dates before this are placeholders in the libraries named by {@code placeholder-date-libraries} (ADR-0054). */
	static final LocalDate PLACEHOLDER_DATES_BEFORE = LocalDate.of(1950, 1, 1);

	/**
	 * A collection library whose rows are imported under its service id.
	 * @param host lower-case host of the base URL; detail URLs must be on it
	 * @param origin scheme, host and port of the service base URL; detail URLs are moved onto it
	 */
	record Library(long serviceId, long collectionId, String platformType, String host, String origin,
			Instant collectedAt, boolean placeholderDates) {

		boolean addedLater() {
			return collectionId >= FIRST_COLLECTED_ONLY_ID;
		}

	}

	/**
	 * The collection libraries: the ones to import, and the skip reason of the others' rows.
	 * @param imported by collection id
	 * @param skipped collection id → skip reason of its rows
	 */
	record Libraries(Map<Long, Library> imported, Map<Long, String> skipped) {
	}

	/**
	 * One holding row that passed the filters.
	 * @param publishedOn the parsed date; null when missing, unreadable, in the future or a placeholder
	 */
	record Row(Library library, String itemKey, String detailUrl, String contentType, String title, String authorRaw,
			String publisher, String publishedRaw, LocalDate publishedOn, String supplier, String altCode,
			Instant firstSeenAt) {
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
	 * With {@code excludeCandidates} (a dry run of cycle 12), a candidate library that would be imported is left out
	 * instead: {@value #EXCLUDED_CANDIDATE}, not counted in {@code candidateLibraries}. Libraries skipped for the
	 * reasons above keep those reasons, so only the candidates' imported rows differ from a run without it.
	 * @param placeholderDateLibraries collection ids whose dates before 1950 are placeholders
	 * @param excludeCandidates leave out the candidate libraries ({@link Library#candidate()})
	 */
	Libraries libraries(Map<Long, Long> links, List<LibraryRef> serviceLibraries, Set<Long> placeholderDateLibraries,
			boolean excludeCandidates, ImportReport report) throws SQLException {
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
					skipped.put(collectionId, "library_not_in_service");
					continue;
				}
				Library library = new Library(ref.id(), collectionId, ref.platformType().name(), hostOf(ref.baseUrl()),
						originOf(ref.baseUrl()), instant(collected), placeholderDateLibraries.contains(collectionId));
				if (excludeCandidates && library.candidate()) {
					skipped.put(collectionId, EXCLUDED_CANDIDATE);
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
		return new Libraries(imported, skipped);
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
					sink.accept(new Row(library, rs.getString(2), onOrigin(rs.getString(3), library.origin()),
							rs.getString(4), rs.getString(7),
							rs.getString(8), share(shared, rs.getString(9)), publishedRaw, publishedOn,
							share(shared, rs.getString(12)), rs.getString(13), instant(rs.getString(14))));
				}
			}
		}
	}

	/** The first filter a row fails, or null (the order of the checks gives the reason that is counted). */
	private static String skipReason(ResultSet rs, Library library) throws SQLException {
		if (rs.getString(6) != null) {
			return "removed";
		}
		String type = rs.getString(4);
		if (type == null || EXCLUDED_KINDS.contains(type)) {
			return "kind_" + String.valueOf(type).toLowerCase(Locale.ROOT);
		}
		String kind = rs.getString(5);
		if (kind != null && SOUND_VIDEO_KINDS.contains(kind.strip())) {
			return "kind_sound_video";
		}
		String detailUrl = rs.getString(3);
		if (detailUrl == null || detailUrl.isBlank()) {
			return "no_detail_url";
		}
		String detailHost = httpHostOf(detailUrl);
		if (detailHost == null) {
			return "detail_url_not_http";
		}
		if (!detailHost.equals(library.host())) {
			return "detail_host_mismatch";
		}
		String title = rs.getString(7);
		if (title == null || title.isBlank()) {
			return "no_title";
		}
		return null;
	}

	/** Same strings (publishers, suppliers) share one instance to keep memory small. */
	private static String share(Map<String, String> shared, String value) {
		return value == null ? null : shared.computeIfAbsent(value, v -> v);
	}

	/**
	 * Whether the collected base URL is the service library's site: same host and path, any scheme and port. The
	 * service keeps the address a site redirects to (docs/library-maintenance.md 4-7: often http to https on another
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
			return service.getHost() != null && service.getHost().equalsIgnoreCase(collected.getHost())
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

	/** Host name in lower case, the port left out (the host throttle works on host names, ADR-0022). */
	static String hostOf(String url) {
		try {
			String host = URI.create(url).getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** Host of an http(s) URL; null for any other scheme or a URL that cannot be read. */
	static String httpHostOf(String url) {
		try {
			URI uri = URI.create(url.strip());
			String scheme = uri.getScheme();
			if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
				return null;
			}
			return uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
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
