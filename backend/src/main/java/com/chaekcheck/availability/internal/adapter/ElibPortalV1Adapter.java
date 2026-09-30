package com.chaekcheck.availability.internal.adapter;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.library.PlatformType;

/**
 * Regional and education-office integrated e-library portals ({@code {portal}/elib}, research.md 12). One portal
 * shows books from several suppliers, with loaned and reservation counts but never holdings.
 * <ul>
 * <li>Page 1: {@code module/elib/search/index.do}. Shows the site's verdict ("대출 가능 여부") where the portal
 * fills it (Busan, Daegu, Gyeonggi); Gyeongbuk and Jeonbuk leave it blank.</li>
 * <li>Page 2 and later: the site ignores {@code viewPage} on index.do and loads later pages from
 * {@code module/elib/search/table.do} with every field of its search form. That fragment leaves the verdict blank
 * everywhere, and on Gyeongbuk has no counts at all (checked 2026-09-28).</li>
 * </ul>
 * Rows differ a little between portals, so fields are read from their labels:
 * <pre>
 * div.row a.name.goDetail[data-book_idx][data-type]   title and detail page key
 * "대출 가능 여부: 대출 가능 | 대출 : 0 | 예약 : 0"       verdict (may be blank), loaned, reservations
 * "저자 : .. │ 출판사 : .." (Gyeonggi) or plain p: author, "publisher[, date]"
 * </pre>
 * There is no title-only field: {@code search_type} means different things on each portal, so the default search
 * is used. Author and publisher searches use the site's facet filters ({@code author_name}, {@code book_pubname}) on
 * table.do from page 1, together with the same {@code search_text}; the filters keep only exact matches (Busan
 * author "김달" 11 rows to 3, ADR-0026, user decision), unlike the partial match of other screens. On
 * those table.do pages the verdict is blank on Busan, Daegu and Gyeonggi too. Detail pages are {@code module/elib/book/view.do?menu_idx=10&book_idx=N&type=T}, the same
 * {@code menu_idx} the search page uses (works on Gyeongbuk and Jeonbuk, 2026-09-28).
 * <p>
 * The menu id is 10 unless the library's {@code base_url} names another one, e.g.
 * {@code https://ebook.gwe.go.kr/elib?menu_idx=2} (Gangwon: 10 answers an error page for search and detail,
 * checked 2026-09-29; user decision "검색 메뉴 번호가 url이야"). Search and detail pages then both use it.
 */
@Component
public class ElibPortalV1Adapter implements PlatformAdapter {

	static final int PAGE_SIZE = 10;

	/** Menu id the search and detail pages are opened with. */
	static final String MENU_IDX = "10";

	private static final Pattern TOTAL_TEXT = Pattern.compile("총\\s*([\\d,]+)\\s*건");

	private static final Pattern TOTAL_SCRIPT = Pattern.compile("#book_totalDataCount'\\)\\.text\\('([\\d,]+)'\\)");

	/** Jeonbuk's table.do: only the script that fills div.search-info ("총 <b>58</b>건"). */
	private static final Pattern TOTAL_INFO_SCRIPT = Pattern.compile("총\\s*<b>([\\d,]+)</b>\\s*건");

	/** The value ends at a separator, at the next "대출 :" count or at the end. */
	private static final Pattern VERDICT = Pattern
		.compile("대출\\s*가능\\s*여부\\s*:\\s*([^|]*?)\\s*(?:\\||대출\\s*:|$)");

	private static final Pattern LOANED = Pattern.compile("대출\\s*:\\s*([\\d,]+)");

	private static final Pattern RESERVED = Pattern.compile("예약\\s*:\\s*([\\d,]+)");

	private static final Pattern AUTHOR = Pattern.compile("저자\\s*:\\s*([^|]+)");

	private static final Pattern PUBLISHER = Pattern.compile("출판사\\s*:\\s*([^|]+)");

	private static final Pattern TRAILING_DATE = Pattern.compile(",\\s*\\d{4}-\\d{2}-\\d{2}$");

	@Override
	public PlatformType platform() {
		return PlatformType.ELIB_PORTAL_V1;
	}

	@Override
	public int pageSize() {
		return PAGE_SIZE;
	}

	/**
	 * TITLE: page 1 {@code index.do}, later pages {@code table.do}. AUTHOR and PUBLISHER: {@code table.do} from page 1
	 * with the filter ({@code author_name} or {@code book_pubname}) and {@code search_text} both set to the text,
	 * because index.do ignores the filters and an empty {@code search_text} finds nothing (ADR-0026).
	 * {@code search_type} is always empty: its values (AUTHOR, TITLE, PUBLER) are material kinds, not fields.
	 */
	@Override
	public URI searchUri(String baseUrl, SearchQuery query, int page) {
		String menuIdx = menuIdx(baseUrl);
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl).replaceQuery(null);
		if (page == 1 && query.field() == SearchField.TITLE) {
			builder.path("/module/elib/search/index.do").queryParam("menu_idx", menuIdx);
		}
		else {
			builder.path("/module/elib/search/table.do")
				.queryParam("menu_idx", menuIdx)
				.queryParam("type", "")
				.queryParam("search_type", "")
				.queryParam("author_name", query.field() == SearchField.AUTHOR ? query.value() : "")
				.queryParam("book_pubname", query.field() == SearchField.PUBLISHER ? query.value() : "")
				.queryParam("book_year", "")
				.queryParam("device", "");
		}
		return builder.queryParam("search_text", query.value())
			.queryParam("rowCount", PAGE_SIZE)
			.queryParam("viewPage", page)
			.encode()
			.build()
			.toUri();
	}

	/**
	 * table.do is a bare fragment that loses the site's layout in a new window (checked 2026-09-29), so
	 * "도서관에서 보기" opens the portal's own search page for the text instead. index.do cannot apply the author
	 * or publisher filter, so that page shows the default search.
	 */
	@Override
	public URI librarySearchUri(String baseUrl, SearchQuery query, URI fetchedUri) {
		if (fetchedUri.getPath().endsWith("/index.do")) {
			return fetchedUri;
		}
		return UriComponentsBuilder.fromUriString(baseUrl)
			.replaceQuery(null)
			.path("/module/elib/search/index.do")
			.queryParam("menu_idx", menuIdx(baseUrl))
			.queryParam("search_text", query.value())
			.encode()
			.build()
			.toUri();
	}

	private static String menuIdx(String baseUrl) {
		String fromBaseUrl = UriComponentsBuilder.fromUriString(baseUrl).build().getQueryParams().getFirst("menu_idx");
		return fromBaseUrl == null || fromBaseUrl.isBlank() ? MENU_IDX : fromBaseUrl;
	}

	/** Detail pages need the same menu id as the search page they were found on. */
	@Override
	public URI detailUri(URI searchUri, String detailPath) {
		URI resolved = searchUri.resolve(detailPath);
		String menuIdx = UriComponentsBuilder.fromUri(searchUri).build().getQueryParams().getFirst("menu_idx");
		return menuIdx == null ? resolved
				: URI.create(resolved.toString().replaceFirst("([?&])menu_idx=[^&]*", "$1menu_idx=" + menuIdx));
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		Charset charset = PlatformAdapter.charsetOr(declaredCharset, charset());
		Document doc = HtmlParsing.document(body, charset);
		Elements rows = doc.select("div.row:has(a.name.goDetail)");
		Integer total = total(doc, new String(body, charset));
		if (rows.isEmpty()) {
			if ((total != null && total == 0) || doc.text().contains("찾으시는 자료가 없습니다")) {
				return new ParsedPage(0, List.of());
			}
			throw UpstreamException.error("ELIB_PORTAL_V1: no rows and no empty-result text");
		}
		if (total == null) {
			throw UpstreamException.error("ELIB_PORTAL_V1: total count not found");
		}
		List<ParsedItem> items = new ArrayList<>();
		for (Element row : rows) {
			items.add(item(row));
		}
		return new ParsedPage(total, items);
	}

	/**
	 * index.do: {@code #book_totalDataCount} or "총 N 건"; table.do: only in the script that fills those, either
	 * {@code #book_totalDataCount} (Busan, Daegu, Gyeongbuk) or {@code div.search-info} (Jeonbuk, 2026-09-29).
	 */
	private static Integer total(Document doc, String html) {
		Element count = doc.selectFirst("#book_totalDataCount");
		if (count != null && count.text().matches("[\\d,]+")) {
			return Integer.parseInt(count.text().replace(",", ""));
		}
		Element info = doc.selectFirst("div.search-info");
		if (info != null) {
			Matcher m = TOTAL_TEXT.matcher(info.text());
			if (m.find()) {
				return HtmlParsing.number(m, 1);
			}
		}
		Matcher script = TOTAL_SCRIPT.matcher(html);
		if (script.find()) {
			return HtmlParsing.number(script, 1);
		}
		Matcher infoScript = TOTAL_INFO_SCRIPT.matcher(html);
		return infoScript.find() ? HtmlParsing.number(infoScript, 1) : null;
	}

	private static ParsedItem item(Element row) {
		Element name = HtmlParsing.required(row, "a.name.goDetail", "ELIB_PORTAL_V1");
		String title = name.text().strip();
		Element info = name.parent();
		String text = labelled(info);
		SiteVerdict verdict = SiteVerdict.BLANK;
		Matcher v = VERDICT.matcher(text);
		if (v.find()) {
			verdict = SiteVerdict.of(v.group(1));
		}
		Integer loaned = find(LOANED, text);
		Integer reservations = find(RESERVED, text);
		Elements paragraphs = info.select("> p");
		String author = labelOr(AUTHOR, text, paragraphs, 0);
		String publisher = labelOr(PUBLISHER, text, paragraphs, 1);
		if (publisher != null) {
			publisher = HtmlParsing.blankToNull(TRAILING_DATE.matcher(publisher).replaceFirst(""));
		}
		return new ParsedItem(title, author, publisher, null, null, loaned, reservations, detailPath(name), verdict);
	}

	/**
	 * Row text with the separator spans ({@code span.txt-bar}, {@code span.web-line}, whose {@code &nbsp;} jsoup
	 * turns into ordinary spaces) and {@code │} turned into {@code |}.
	 */
	private static String labelled(Element info) {
		StringBuilder text = new StringBuilder();
		for (Element p : info.select("> p")) {
			text.append(separated(p)).append(" | ");
		}
		return text.toString().replaceAll("\\s*\\|[\\s|]*", " | ");
	}

	private static String separated(Element element) {
		Element copy = element.clone();
		copy.select("span.txt-bar, span.web-line").forEach(span -> span.replaceWith(new TextNode(" | ")));
		return copy.text().replace('│', '|');
	}

	/** Labelled value ("저자 : ..") when the portal labels it, otherwise the n-th plain paragraph. */
	private static String labelOr(Pattern label, String text, Elements paragraphs, int index) {
		Matcher m = label.matcher(text);
		if (m.find()) {
			return HtmlParsing.blankToNull(m.group(1));
		}
		if (paragraphs.size() <= index) {
			return null;
		}
		String value = paragraphs.get(index).text();
		return value.contains(":") ? null : HtmlParsing.blankToNull(value);
	}

	private static Integer find(Pattern pattern, String text) {
		Matcher m = pattern.matcher(text);
		return m.find() ? HtmlParsing.number(m, 1) : null;
	}

	/** Relative to the search page ({@code ../module/elib/search/..}). */
	private static String detailPath(Element name) {
		String bookIdx = name.attr("data-book_idx");
		if (bookIdx.isBlank()) {
			return null;
		}
		String type = name.attr("data-type").isBlank() ? "EBK" : name.attr("data-type");
		return UriComponentsBuilder.fromPath("../book/view.do")
			.queryParam("menu_idx", MENU_IDX)
			.queryParam("book_idx", bookIdx)
			.queryParam("type", type)
			.encode()
			.build()
			.toUriString();
	}

	/** The detail page's "대출 가능 여부: 예약 가능 | 대출 : 5 | ..." line. */
	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		for (Element li : doc.select("div.sinfo li")) {
			String text = separated(li);
			Matcher v = VERDICT.matcher(text);
			if (text.contains("여부") && v.find()) {
				return ParsedDetail.verdict(SiteVerdict.of(v.group(1)));
			}
		}
		throw UpstreamException.error("ELIB_PORTAL_V1: detail page has no 대출 가능 여부");
	}

}
