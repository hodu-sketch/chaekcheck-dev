package com.chaekcheck.catalog.internal.search;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.catalog.internal.normalize.CatalogText;
import com.chaekcheck.catalog.internal.normalize.Contributors.Role;
import com.chaekcheck.library.SearchTarget;

/**
 * {@link BookSearch} on PostgreSQL with pg_bigm (ADR-0032). Words match the normalized columns with {@code LIKE};
 * the GIN {@code gin_bigm_ops} indexes serve them. Library filtering uses the plan ids as a parameter only (no join
 * with another module's table): {@code unnest(:libraryIds, :tiers)}.
 */
public class PgBigmBookSearch implements BookSearch {

	/** Collection dates are shown as Korean calendar days. */
	static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	private final JdbcClient jdbc;

	public PgBigmBookSearch(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public BookPage search(BookQuery query, List<SearchTarget> plan) {
		if (plan.isEmpty()) {
			return new BookPage(List.of(), query.page(), query.pageSize(), 0, false, null, null);
		}
		Map<String, Object> params = new HashMap<>();
		planParams(plan, params);
		String where = conditions(query, params);
		String exactTitle = query.title() == null ? "" : CatalogText.norm(query.title());
		params.put("exactTitleNorm", exactTitle);
		params.put("limit", query.pageSize() + 1);
		params.put("offset", (long) (query.page() - 1) * query.pageSize());
		String defaultOrder = "s.tier, exact_title DESC, s.library_count DESC, b.published_on DESC NULLS LAST, b.id";
		String order = switch (query.sort()) {
			case DEFAULT -> defaultOrder;
			case NEWEST -> "b.published_on DESC NULLS LAST, " + defaultOrder;
			case OLDEST -> "b.published_on ASC NULLS LAST, " + defaultOrder;
		};
		String sql = """
				WITH plan AS (
				  SELECT * FROM unnest(CAST(:libraryIds AS bigint[]), CAST(:tiers AS integer[])) AS p(library_id, tier)
				), matched AS (
				  SELECT b.id FROM catalog.book b WHERE %s
				), scored AS (
				  SELECT h.book_id, min(p.tier) AS tier, count(DISTINCT h.library_id) AS library_count,
				    min(sn.collected_at) AS collected_from, max(sn.collected_at) AS collected_to
				  FROM matched m
				  JOIN catalog.holding h ON h.book_id = m.id
				  JOIN plan p ON p.library_id = h.library_id
				  LEFT JOIN catalog.catalog_library_snapshot sn ON sn.library_id = h.library_id
				  GROUP BY h.book_id
				)
				SELECT b.id, b.title, b.author_display, b.publisher, b.published_on, s.library_count, s.tier,
				  (b.main_title_norm = :exactTitleNorm) AS exact_title,
				  count(*) OVER () AS total_count,
				  min(s.collected_from) OVER () AS collected_from, max(s.collected_to) OVER () AS collected_to
				FROM scored s JOIN catalog.book b ON b.id = s.book_id
				ORDER BY %s
				LIMIT :limit OFFSET :offset""".formatted(where, order);

		long[] total = { 0 };
		LocalDate[] range = { null, null };
		List<BookItem> items = jdbc.sql(sql).params(params).query((rs, rowNum) -> {
			total[0] = rs.getLong("total_count");
			range[0] = koreanDate(rs.getTimestamp("collected_from"));
			range[1] = koreanDate(rs.getTimestamp("collected_to"));
			return new BookItem(rs.getLong("id"), rs.getString("title"), rs.getString("author_display"),
					rs.getString("publisher"), localDate(rs, "published_on"), rs.getInt("library_count"),
					JoinTier.values()[rs.getInt("tier")], rs.getBoolean("exact_title"));
		}).list();
		boolean hasMore = items.size() > query.pageSize();
		List<BookItem> page = hasMore ? items.subList(0, query.pageSize()) : items;
		if (items.isEmpty() && query.page() > 1) {
			// past the last page: the total still comes from a count
			total[0] = count(where, params);
		}
		return new BookPage(List.copyOf(page), query.page(), query.pageSize(), total[0], hasMore, range[0], range[1]);
	}

	@Override
	public Optional<BookDetail> find(long bookId, List<SearchTarget> plan) {
		Optional<BookDetail> book = jdbc.sql("""
				SELECT id, title, author_display, publisher, published_on, isbn13 FROM catalog.book WHERE id = :id""")
			.param("id", bookId)
			.query((rs, rowNum) -> new BookDetail(rs.getLong("id"), rs.getString("title"),
					rs.getString("author_display"), rs.getString("publisher"), localDate(rs, "published_on"),
					rs.getString("isbn13"), 0, List.of()))
			.optional();
		if (book.isEmpty()) {
			return book;
		}
		Map<Long, OffsetDateTime> collected = new LinkedHashMap<>();
		if (!plan.isEmpty()) {
			Map<Long, OffsetDateTime> held = new HashMap<>();
			jdbc.sql("""
					SELECT DISTINCT h.library_id, sn.collected_at
					FROM catalog.holding h
					LEFT JOIN catalog.catalog_library_snapshot sn ON sn.library_id = h.library_id
					WHERE h.book_id = :id AND h.library_id = ANY(CAST(:libraryIds AS bigint[]))""")
				.param("id", bookId)
				.param("libraryIds", plan.stream().map(SearchTarget::id).toArray(Long[]::new))
				.query((ResultSet rs) -> {
					Timestamp at = rs.getTimestamp("collected_at");
					held.put(rs.getLong("library_id"), at == null ? null : at.toInstant().atZone(KOREA).toOffsetDateTime());
				});
			for (SearchTarget target : plan) {
				if (held.containsKey(target.id())) {
					collected.put(target.id(), held.get(target.id()));
				}
			}
		}
		List<LibraryCollected> libraries = new ArrayList<>();
		collected.forEach((id, at) -> libraries.add(new LibraryCollected(id, at)));
		BookDetail b = book.get();
		return Optional.of(new BookDetail(b.id(), b.title(), b.authorDisplay(), b.publisher(), b.publishedOn(),
				b.isbn13(), libraries.size(), List.copyOf(libraries)));
	}

	private long count(String where, Map<String, Object> params) {
		return jdbc.sql("""
				SELECT count(DISTINCT h.book_id) FROM catalog.book b
				JOIN catalog.holding h ON h.book_id = b.id
				WHERE h.library_id = ANY(CAST(:libraryIds AS bigint[])) AND %s""".formatted(where))
			.params(params)
			.query(Long.class)
			.single();
	}

	private static void planParams(List<SearchTarget> plan, Map<String, Object> params) {
		Long[] ids = new Long[plan.size()];
		Integer[] tiers = new Integer[plan.size()];
		for (int i = 0; i < plan.size(); i++) {
			ids[i] = plan.get(i).id();
			tiers[i] = JoinTier.of(plan.get(i)).ordinal();
		}
		params.put("libraryIds", ids);
		params.put("tiers", tiers);
	}

	/** WHERE conditions on {@code b} for the filled fields; values go to {@code params}. */
	static String conditions(BookQuery query, Map<String, Object> params) {
		List<String> conditions = new ArrayList<>();
		if (query.title() != null) {
			if (query.exactTitle()) {
				params.put("titleExact", CatalogText.norm(query.title()));
				conditions.add("(b.main_title_norm = :titleExact OR b.title_norm = :titleExact)");
			}
			else {
				words(query.titleWords(), "t", "b.title_norm", conditions, params);
			}
		}
		if (query.publisher() != null) {
			if (query.exactPublisher()) {
				params.put("publisherExact", CatalogText.publisherNorm(query.publisher()));
				conditions.add("b.publisher_norm = :publisherExact");
			}
			else {
				words(query.publisherWords(), "p", "b.publisher_norm", conditions, params);
			}
		}
		if (query.author() != null) {
			List<String> person = new ArrayList<>();
			if (query.exactAuthor()) {
				params.put("authorExact", CatalogText.nameExact(query.author()));
				person.add("c.name_exact = :authorExact");
			}
			else {
				words(query.authorWords(), "a", "c.name_norm", person, params);
			}
			params.put("roles", query.roles().stream().map(Role::name).sorted().toList());
			// every word in one person's name (ADR-0032), in the searched roles
			conditions.add("EXISTS (SELECT 1 FROM catalog.book_contributor c WHERE c.book_id = b.id"
					+ " AND c.role IN (:roles) AND " + String.join(" AND ", person) + ")");
		}
		if (conditions.isEmpty()) {
			throw new IllegalArgumentException("a book query needs at least one field");
		}
		return String.join(" AND ", conditions);
	}

	private static void words(List<String> words, String prefix, String column, List<String> conditions,
			Map<String, Object> params) {
		for (int i = 0; i < words.size(); i++) {
			String name = prefix + i;
			params.put(name, "%" + likeEscape(words.get(i)) + "%");
			conditions.add(column + " LIKE :" + name);
		}
	}

	/** Escapes {@code %}, {@code _} and the escape character itself (default LIKE escape is backslash). */
	static String likeEscape(String word) {
		return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
		Date date = rs.getDate(column);
		return date == null ? null : date.toLocalDate();
	}

	private static LocalDate koreanDate(Timestamp at) {
		return at == null ? null : at.toInstant().atZone(KOREA).toLocalDate();
	}

}
