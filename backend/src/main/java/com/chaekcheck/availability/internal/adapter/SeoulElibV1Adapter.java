package com.chaekcheck.availability.internal.adapter;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.library.PlatformType;

/**
 * 서울도서관's own integrated e-book site ({@code https://elib.seoul.go.kr}), read through the JSON its own pages
 * call (docs/library-maintenance.md 4-14, docs/maintenance/platforms.md 11, 호출 시험 2026-10-20):
 * <pre>
 * /api/contents/search?contentType=EB&amp;searchKeyword=..&amp;searchOption=1|2|3&amp;currentCount=N&amp;pageCount=10..
 *     search (title, author, publisher), {totalCount, ContentDataList[]}; counts are strings
 * /api/contents/{contentsKey}   one book, Contents[0] (its page is /contents/detail.do?no={contentsKey})
 * row: contentsKey, title, author, publisher, ownerCode (supplier), contentsType
 *   TY01 owned: b2bCopys (detail: contentsCopys), currentLoanCount, currentResvCount   holdings, loaned, reservations
 *   TY02 subscription: "대출 수 제한 없음" (the library's own guide), its counts mean nothing
 *   TY03 audiobook (not in contentType=EB answers): left out, as audiobooks are never imported
 * ownerCode OV: overseas e-books lent on the OverDrive site, not here: left out
 * </pre>
 * The site has no search page with the query in its address (the search runs in a script), so "도서관에서 보기" opens
 * the front page.
 */
@Component
public class SeoulElibV1Adapter implements PlatformAdapter {

	static final int PAGE_SIZE = 10;

	private static final String SCREEN = "SEOUL_ELIB_V1";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	static final String OWNED = "TY01";

	static final String SUBSCRIPTION = "TY02";

	/** OverDrive: overseas e-books, borrowed on seoullib.overdrive.com. */
	static final String OVERDRIVE = "OV";

	/** {@code ownerCode} names as the site's own detail script ({@code r_detail.js}) shows them (2026-10-20). */
	static final Map<String, String> SUPPLIERS = Map.of("OD", "ECO", "BQ", "북큐브", "BX", "OPMS", "YE", "예스24", "YB",
			"Y2BOOKS", "CP", "컨텐츠포탈", "AL", "알라딘", "AD", "오디언", "OV", "오버드라이브");

	@Override
	public PlatformType platform() {
		return PlatformType.SEOUL_ELIB_V1;
	}

	@Override
	public int pageSize() {
		return PAGE_SIZE;
	}

	@Override
	public URI searchUri(String baseUrl, SearchQuery query, int page) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/api/contents/search")
			.queryParam("libCode", "")
			.queryParam("contentType", "EB")
			.queryParam("searchKeyword", query.value())
			.queryParam("searchOption", searchOption(query.field()))
			.queryParam("sortOption", 1)
			.queryParam("innerSearchYN", "N")
			.queryParam("innerKeyword", "")
			.queryParam("currentCount", page)
			.queryParam("pageCount", PAGE_SIZE)
			.queryParam("loanable", "")
			.queryParam("isTotal", "false")
			.queryParam("showType", "L")
			.queryParam("searchCombine", "N")
			.encode()
			.build()
			.toUri();
	}

	/** {@code searchOption}: 1 title, 2 author, 3 publisher (4 is everything; 호출 시험 2026-10-20: 김영하, 문학동네). */
	static int searchOption(SearchField field) {
		return switch (field) {
			case TITLE -> 1;
			case AUTHOR -> 2;
			case PUBLISHER -> 3;
		};
	}

	/** "도서관에서 보기": the JSON is not for people and the site's search page keeps no query, so the front page. */
	@Override
	public URI librarySearchUri(String baseUrl, SearchQuery query, URI fetchedUri) {
		return URI.create((baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "main");
	}

	/** The book's page {@code /contents/detail.do?no=} is read as {@code /api/contents/{no}}. */
	@Override
	public URI detailFetchUri(URI detailUri) {
		String key = UriComponentsBuilder.fromUri(detailUri).build().getQueryParams().getFirst("no");
		if (key == null || !detailUri.getPath().endsWith("/contents/detail.do")) {
			return detailUri;
		}
		return UriComponentsBuilder.fromUri(detailUri)
			.replacePath("/api/contents/{key}")
			.replaceQuery(null)
			.encode()
			.buildAndExpand(key)
			.toUri();
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		JsonNode root = json(body);
		String total = text(root, "totalCount");
		JsonNode rows = root.get("ContentDataList");
		if (total == null || !total.matches("\\d+") || rows == null || !rows.isArray()) {
			throw UpstreamException.error(SCREEN + ": search answer has no totalCount or ContentDataList");
		}
		List<ParsedItem> items = new ArrayList<>();
		for (JsonNode row : rows) {
			ParsedItem item = item(row);
			if (item != null) {
				items.add(item);
			}
		}
		return new ParsedPage(Integer.parseInt(total), items);
	}

	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		JsonNode contents = json(body).get("Contents");
		if (contents == null || !contents.isArray() || contents.isEmpty()) {
			throw UpstreamException.error(SCREEN + ": detail answer has no Contents (no such book?)");
		}
		JsonNode book = contents.get(0);
		String type = text(book, "contentsType");
		if (OVERDRIVE.equals(text(book, "ownerCode"))) {
			return ParsedDetail.verdict(SiteVerdict.OTHER);
		}
		if (SUBSCRIPTION.equals(type)) {
			return new ParsedDetail(null, null, null, SiteVerdict.SUBSCRIPTION);
		}
		if (!OWNED.equals(type)) {
			return ParsedDetail.verdict(SiteVerdict.OTHER);
		}
		Integer copies = number(book, "contentsCopys");
		return ParsedDetail.counted(copies != null ? copies : required(book, "b2bCopys"),
				required(book, "currentLoanCount"), required(book, "currentResvCount"));
	}

	/** A row, or null for rows this service does not show (OverDrive, audiobooks, other kinds). */
	private static ParsedItem item(JsonNode row) {
		String key = text(row, "contentsKey");
		String title = text(row, "title");
		if (key == null || title == null) {
			throw UpstreamException.error(SCREEN + ": row without contentsKey or title");
		}
		String owner = text(row, "ownerCode");
		String type = text(row, "contentsType");
		if (OVERDRIVE.equals(owner) || !(OWNED.equals(type) || SUBSCRIPTION.equals(type))) {
			return null;
		}
		String detailPath = UriComponentsBuilder.fromPath("/contents/detail.do")
			.queryParam("no", key)
			.encode()
			.build()
			.toUriString();
		String supplier = owner == null ? null : SUPPLIERS.getOrDefault(owner, owner);
		if (SUBSCRIPTION.equals(type)) {
			return new ParsedItem(title, text(row, "author"), text(row, "publisher"), supplier, null, null, null,
					detailPath, SiteVerdict.SUBSCRIPTION);
		}
		return ParsedItem.counted(title, text(row, "author"), text(row, "publisher"), supplier,
				required(row, "b2bCopys"), required(row, "currentLoanCount"), required(row, "currentResvCount"),
				detailPath);
	}

	private static int required(JsonNode node, String field) {
		Integer value = number(node, field);
		if (value == null) {
			throw UpstreamException.error(SCREEN + ": " + field + " is not a number");
		}
		return value;
	}

	/** A whole number written as a number or as digits; null when missing, null or anything else. */
	private static Integer number(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
			return value.intValue();
		}
		if (value.isString() && value.asString().strip().matches("\\d{1,9}")) {
			return Integer.parseInt(value.asString().strip());
		}
		return null;
	}

	/** A value's text (numbers too); null when missing, null or blank. */
	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || !value.isValueNode()) {
			return null;
		}
		return HtmlParsing.blankToNull(value.asString());
	}

	private static JsonNode json(byte[] body) {
		try {
			JsonNode root = JSON.readTree(body);
			if (root == null || !root.isObject()) {
				throw UpstreamException.error(SCREEN + ": answer is not a JSON object");
			}
			return root;
		}
		catch (JacksonException ex) {
			throw new UpstreamException(UpstreamException.Kind.ERROR, SCREEN + ": not readable JSON", ex);
		}
	}

}
