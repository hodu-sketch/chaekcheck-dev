package com.chaekcheck.usagelog.internal.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.usagelog.UsageRequest;
import com.chaekcheck.usagelog.internal.VisitKeys;
import com.chaekcheck.usagelog.internal.buffer.UsageRow;

/**
 * Checks one usage report (design-delta "API", cycle 15 step 6) and turns it into a row, or into a
 * {@code rejected_event} row with the reason. Library numbers are checked later, once per write (plan C5).
 * Fields not listed are ignored. Picks category codes are not checked against a list: new codes may appear. The
 * optional {@code visitKey} (ADR-0134) is kept when it has the right shape and silently dropped otherwise.
 */
final class UsageEventReader {

	static final int MAX_RAW_BYTES = 2048;

	static final int MAX_REGION_IDS = 20;

	static final int MAX_CATEGORY_LENGTH = 20;

	/** The recommended lists (ADR-0126; {@code kaist} user 2026-10-19), as {@code screen_event.picks_list} allows. */
	static final Set<String> PICKS_LISTS = Set.of("librarian", "leedongjin", "snu100", "kaist");

	static final Set<String> PLACES = Set.of("result_card", "join_link", "join_guide", "home_page", "logo",
			"summary_bar");

	static final Set<String> SCREENS = Set.of("direct_results", "catalog_results", "book_page", "picks",
			"my_libraries");

	private final JsonMapper json;

	UsageEventReader(JsonMapper json) {
		this.json = json;
	}

	/** A report that cannot be used, with its reason ({@code rejected_event.reason}). */
	static final class Rejected extends RuntimeException {

		final String reason;

		Rejected(String reason) {
			super(reason, null, false, false);
			this.reason = reason;
		}

	}

	UsageRow read(byte[] body, boolean tooLarge, UsageRequest request) {
		String raw = rawValues(body, tooLarge);
		try {
			if (tooLarge) {
				throw new Rejected("malformed");
			}
			JsonNode node = parse(body);
			String visitKey = visitKey(node, request);
			return switch (type(node)) {
				case "library_click" -> new UsageRow.LibraryClick(request.receivedAt(), libraryId(node),
						place(node), screen(node), optionalBookId(node), request.loginStatus(), request.clientIp(),
						request.accountId(), visitKey, raw);
				case "picks_list_view" -> screen(request, visitKey, raw, "picks_list_view", picksList(node),
						category(node), null, null, null, null);
				case "picks_book_view" -> screen(request, visitKey, raw, "picks_book_view", picksList(node),
						category(node), bookId(node), null, null, null);
				case "region_change" -> screen(request, visitKey, raw, "region_change", null, null, null,
						regionIds(node, "residenceRegionIds"), regionIds(node, "workRegionIds"), null);
				case "my_library_add", "my_library_remove" -> screen(request, visitKey, raw, type(node), null, null,
						null, null, null, libraryId(node));
				default -> throw new Rejected("unknown_type");
			};
		}
		catch (Rejected rejected) {
			return new UsageRow.Rejected(request.receivedAt(), request.clientIp(), rejected.reason, raw);
		}
	}

	private static UsageRow.ScreenEvent screen(UsageRequest request, String visitKey, String raw, String kind,
			String list, String category, Long bookId, List<Long> residence, List<Long> work, Long libraryId) {
		return new UsageRow.ScreenEvent(request.receivedAt(), kind, list, category, bookId, residence, work,
				libraryId, request.loginStatus(), request.clientIp(), request.accountId(), visitKey, raw);
	}

	/**
	 * The tab's visit key (ADR-0134): the body field {@code visitKey} when it is a string of the right shape, else the
	 * {@code X-Visit-Key} header the capture read, else null. Never a reason to reject the report.
	 */
	private static String visitKey(JsonNode node, UsageRequest request) {
		JsonNode value = node.get(VisitKeys.FIELD);
		if (value != null && value.isString()) {
			String key = VisitKeys.normalize(value.asString());
			if (key != null) {
				return key;
			}
		}
		return request.visitKey();
	}

	private JsonNode parse(byte[] body) {
		if (body == null || body.length == 0) {
			throw new Rejected("malformed");
		}
		try {
			JsonNode node = json.readTree(body);
			if (node == null || !node.isObject()) {
				throw new Rejected("malformed");
			}
			return node;
		}
		catch (JacksonException ex) {
			throw new Rejected("malformed");
		}
	}

	private static String type(JsonNode node) {
		JsonNode type = node.get("type");
		if (type == null || !type.isString()) {
			throw new Rejected("unknown_type");
		}
		return type.asString();
	}

	private static long libraryId(JsonNode node) {
		Long id = positive(node.get("libraryId"));
		if (id == null) {
			throw new Rejected("invalid_value");
		}
		return id;
	}

	private static String place(JsonNode node) {
		JsonNode place = node.get("place");
		if (place == null || !place.isString() || !PLACES.contains(place.asString())) {
			throw new Rejected("invalid_place");
		}
		return place.asString();
	}

	private static String screen(JsonNode node) {
		JsonNode screen = node.get("screen");
		if (screen == null || !screen.isString() || !SCREENS.contains(screen.asString())) {
			throw new Rejected("invalid_screen");
		}
		return screen.asString();
	}

	/** Missing or null means no book; anything else must be a positive whole number. */
	private static Long optionalBookId(JsonNode node) {
		JsonNode bookId = node.get("bookId");
		if (bookId == null || bookId.isNull()) {
			return null;
		}
		return bookId(node);
	}

	private static long bookId(JsonNode node) {
		Long id = positive(node.get("bookId"));
		if (id == null) {
			throw new Rejected("invalid_book_id");
		}
		return id;
	}

	/** The recommended list of a picks event: required, one of {@link #PICKS_LISTS} (cycle 21, ADR-0126). */
	private static String picksList(JsonNode node) {
		JsonNode list = node.get("list");
		if (list == null || !list.isString() || !PICKS_LISTS.contains(list.asString())) {
			throw new Rejected("invalid_value");
		}
		return list.asString();
	}

	private static String category(JsonNode node) {
		JsonNode category = node.get("category");
		if (category == null || !category.isString()) {
			throw new Rejected("invalid_value");
		}
		String value = category.asString().strip();
		if (value.isEmpty() || value.chars().anyMatch(Character::isISOControl) || value.codePointCount(0, value.length()) > MAX_CATEGORY_LENGTH) {
			throw new Rejected("invalid_value");
		}
		return value;
	}

	private static List<Long> regionIds(JsonNode node, String field) {
		JsonNode ids = node.get(field);
		if (ids == null || !ids.isArray() || ids.size() > MAX_REGION_IDS) {
			throw new Rejected("invalid_value");
		}
		List<Long> values = new ArrayList<>();
		for (JsonNode id : ids) {
			Long value = positive(id);
			if (value == null) {
				throw new Rejected("invalid_value");
			}
			values.add(value);
		}
		return values;
	}

	/** A positive whole JSON number that fits a long, else null. */
	private static Long positive(JsonNode value) {
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
			return null;
		}
		long id = value.asLong();
		return id >= 1 ? id : null;
	}

	/**
	 * What was received, for {@code rejected_event.raw_values} (jsonb): the JSON itself when it is valid and at most
	 * 2KB, otherwise the text cut to 2KB as one JSON string.
	 */
	String rawValues(byte[] body, boolean tooLarge) {
		if (body == null || body.length == 0) {
			return null;
		}
		if (!tooLarge && body.length <= MAX_RAW_BYTES) {
			try {
				JsonNode node = json.readTree(body);
				if (node != null && !node.isMissingNode()) {
					String text = json.writeValueAsString(node);
					// jsonb refuses the NUL character; such a report is kept as text below
					if (!text.contains("\\u0000")) {
						return text;
					}
				}
			}
			catch (JacksonException ex) {
				// kept as text below
			}
		}
		return json.writeValueAsString(cut(body));
	}

	/** At most {@link #MAX_RAW_BYTES} bytes of UTF-8 text, never ending inside a character. */
	private static String cut(byte[] body) {
		int end = Math.min(body.length, MAX_RAW_BYTES);
		while (end > 0 && end < body.length && (body[end] & 0xC0) == 0x80) {
			end--;
		}
		return new String(body, 0, end, StandardCharsets.UTF_8).replace("\u0000", "");
	}

}
