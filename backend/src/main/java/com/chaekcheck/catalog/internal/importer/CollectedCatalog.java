package com.chaekcheck.catalog.internal.importer;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.chaekcheck.catalog.internal.normalize.PublishedDates;
import com.chaekcheck.library.LibraryRef;

/**
 * Reads the collected SQLite (chaekcheck-data {@code ebook-catalog.sqlite}) read-only, in one transaction so all rows
 * come from one point in time while the crawler may still write (WAL). Only plain JDBC is used: the driver
 * ({@code sqlite-jdbc}) is on the importer classpath only.
 */
final class CollectedCatalog {

	/** Kinds never imported [확정]. The user chose to import KIDS_BOOK, PAPER and UNKNOWN (2026-09-30). */
	static final Set<String> EXCLUDED_KINDS = Set.of("AUDIOBOOK", "VIDEO_BOOK");

	record Library(long id, String platformType, Instant collectedAt) {
	}

	record Contents(List<Library> libraries, List<CollectedRow> rows) {
	}

	private CollectedCatalog() {
	}

	static Contents read(Path sqlite, List<LibraryRef> serviceLibraries, LocalDate today, ImportReport report)
			throws SQLException {
		if (!Files.isRegularFile(sqlite)) {
			throw new IllegalArgumentException("Collected SQLite not found: " + sqlite);
		}
		Properties props = new Properties();
		// SQLITE_OPEN_READONLY: the crawler owns the file (handoff "읽기 전용")
		props.setProperty("open_mode", "1");
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath(), props)) {
			connection.setAutoCommit(false);
			Map<Long, Library> libraries = libraries(connection, serviceLibraries, report);
			List<CollectedRow> rows = rows(connection, libraries, today, report);
			connection.rollback();
			return new Contents(List.copyOf(libraries.values()), rows);
		}
	}

	/**
	 * Libraries whose id and base URL both match an enabled service library [확정]. {@code collected_at} is the
	 * collected {@code last_full_success_at} [안]; a library without one uses its newest {@code last_seen_at}.
	 */
	private static Map<Long, Library> libraries(Connection connection, List<LibraryRef> serviceLibraries,
			ImportReport report) throws SQLException {
		Map<Long, LibraryRef> service = new HashMap<>();
		serviceLibraries.forEach(ref -> service.put(ref.id(), ref));
		Map<Long, Library> matched = new HashMap<>();
		String sql = """
				SELECT l.id, l.base_url, l.last_full_success_at,
				  (SELECT max(h.last_seen_at) FROM holding h WHERE h.library_id = l.id) AS last_seen
				FROM library l ORDER BY l.id""";
		try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				LibraryRef ref = service.get(rs.getLong(1));
				if (ref == null || !ref.baseUrl().equals(rs.getString(2))) {
					continue;
				}
				String fullSuccess = rs.getString(3);
				String collected = fullSuccess != null ? fullSuccess : rs.getString(4);
				if (collected == null) {
					continue;
				}
				if (fullSuccess == null) {
					report.librariesWithoutFullSuccess++;
				}
				matched.put(ref.id(), new Library(ref.id(), ref.platformType().name(), instant(collected)));
			}
		}
		report.librariesMatched = matched.size();
		return matched;
	}

	private static List<CollectedRow> rows(Connection connection, Map<Long, Library> libraries, LocalDate today,
			ImportReport report) throws SQLException {
		Map<Long, String> hosts = new HashMap<>();
		List<CollectedRow> rows = new ArrayList<>();
		Map<String, String> shared = new HashMap<>();
		String sql = """
				SELECT h.library_id, l.base_url, h.item_key, h.detail_url, h.content_type, h.removed_at, h.title,
				  h.author_raw, h.publisher, h.published_raw, h.published_on, h.supplier, h.alt_code, h.first_seen_at
				FROM holding h JOIN library l ON l.id = h.library_id""";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setFetchSize(10_000);
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					report.rowsRead++;
					Library library = libraries.get(rs.getLong(1));
					if (library == null) {
						report.skip("library_not_in_service");
						continue;
					}
					if (rs.getString(6) != null) {
						report.skip("removed");
						continue;
					}
					String kind = rs.getString(5);
					if (kind == null || EXCLUDED_KINDS.contains(kind)) {
						report.skip("kind_" + String.valueOf(kind).toLowerCase(Locale.ROOT));
						continue;
					}
					String detailUrl = rs.getString(4);
					if (detailUrl == null || detailUrl.isBlank()) {
						report.skip("no_detail_url");
						continue;
					}
					String baseHost = hosts.computeIfAbsent(library.id(), id -> hostOf(baseUrl(rs)));
					String detailHost = httpHostOf(detailUrl);
					if (detailHost == null) {
						report.skip("detail_url_not_http");
						continue;
					}
					if (!detailHost.equals(baseHost)) {
						report.skip("detail_host_mismatch");
						continue;
					}
					String title = rs.getString(7);
					if (title == null || title.isBlank()) {
						report.skip("no_title");
						continue;
					}
					String publishedRaw = rs.getString(10) != null ? rs.getString(10) : rs.getString(11);
					rows.add(new CollectedRow(library.id(), library.platformType(), rs.getString(3), detailUrl, kind,
							title, rs.getString(8), share(shared, rs.getString(9)), publishedRaw,
							PublishedDates.parse(rs.getString(11) != null ? rs.getString(11) : publishedRaw, today),
							share(shared, rs.getString(12)), rs.getString(13), instant(rs.getString(14))));
				}
			}
		}
		return rows;
	}

	private static String baseUrl(ResultSet rs) {
		try {
			return rs.getString(2);
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Same strings (publishers, suppliers) share one instance to keep the row list small. */
	private static String share(Map<String, String> shared, String value) {
		return value == null ? null : shared.computeIfAbsent(value, v -> v);
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

}
