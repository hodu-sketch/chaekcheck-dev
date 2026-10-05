package com.chaekcheck.availability.internal.adapter;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.library.PlatformType;

/**
 * Aladin e-library site ({@code base_url} = site root + {@code ?nav_code=} of its e-book menu, e.g.
 * {@code https://uc.aladinebook.co.kr?nav_code=ebo1655356114}), HTML in UTF-8 (ADR-0056, plan research 1절 2,
 * 호출 시험 2026-10-05):
 * <pre>
 * {root}/sub/sub.htm?nav_code=..&amp;code=search&amp;search_item=title|author|publisher&amp;search_order=..&amp;view_level=1
 * em.total-list-cnt                total count (b#searchResultCnt above it is always 0: not read)
 * li.line                          one result
 *   h2.book_tit a                  title, link ending in &amp;goods_id=N
 *   span.writer / span.pub         author / publisher (span.bar "|" inside)
 *   span.volumecnt em, span.uselendingcnt em, span.usereservecnt em   보유 / 대출 / 예약
 * {root}/sub/sub.htm?mode=view&amp;nav_code=..&amp;goods_id=..   the book; "보유현황 보유 N, 대출 N, 예약 N(, 누적대출 N)"
 * </pre>
 * Only the first page is read (the page-size argument of the search was not confirmed, ADR-0056): the total is
 * capped at the rows of that page, so there is no "더 보기".
 */
@Component
public class AladinV1Adapter implements PlatformAdapter {

	/** Rows on the first page (2026-10-05: 20 rows for 63 results). */
	static final int PAGE_SIZE = 20;

	private static final String SCREEN = "ALADIN_V1";

	private static final Pattern NUMBER = Pattern.compile("([\\d,]+)");

	private static final Pattern GOODS_ID = Pattern.compile("[?&]goods_id=([^&#]+)");

	@Override
	public PlatformType platform() {
		return PlatformType.ALADIN_V1;
	}

	@Override
	public int pageSize() {
		return PAGE_SIZE;
	}

	@Override
	public URI searchUri(String baseUrl, SearchQuery query, int page) {
		UriComponents base = UriComponentsBuilder.fromUriString(baseUrl).build();
		String navCode = base.getQueryParams().getFirst("nav_code");
		if (navCode == null || navCode.isBlank()) {
			throw UpstreamException.error(SCREEN + ": base URL has no nav_code");
		}
		return UriComponentsBuilder.newInstance()
			.scheme(base.getScheme())
			.host(base.getHost())
			.port(base.getPort())
			.path("/sub/sub.htm")
			.queryParam("nav_code", navCode)
			.queryParam("code", "search")
			.queryParam("search_item", searchItem(query.field()))
			.queryParam("search_order", query.value())
			.queryParam("view_level", 1)
			.encode()
			.build()
			.toUri();
	}

	/**
	 * Values of {@code search_item}. Publisher works too (2026-10-05: "포레스트 웨일" gave its 5 books), so it is used
	 * instead of failing the library (user decision 2026-10-05).
	 */
	static String searchItem(SearchField field) {
		return switch (field) {
			case TITLE -> "title";
			case AUTHOR -> "author";
			case PUBLISHER -> "publisher";
		};
	}

	/** The book's page needs the e-book menu's {@code nav_code}, which the search URL has. */
	@Override
	public URI detailUri(URI searchUri, String detailPath) {
		String navCode = UriComponentsBuilder.fromUri(searchUri).build().getQueryParams().getFirst("nav_code");
		return UriComponentsBuilder.fromUri(searchUri)
			.replaceQuery(null)
			.queryParam("mode", "view")
			.queryParam("nav_code", navCode)
			.queryParam("goods_id", detailPath)
			.build(true)
			.toUri();
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		Matcher total = NUMBER.matcher(HtmlParsing.required(doc, "em.total-list-cnt", SCREEN).text());
		if (!total.find()) {
			throw UpstreamException.error(SCREEN + ": total count is not a number");
		}
		List<ParsedItem> items = new ArrayList<>();
		for (Element line : doc.select("li.line")) {
			items.add(item(line));
		}
		// first page only (see the class comment)
		return new ParsedPage(Math.min(HtmlParsing.number(total, 1), items.size()), items);
	}

	private static ParsedItem item(Element line) {
		Element link = HtmlParsing.required(line, "h2.book_tit a", SCREEN);
		String title = link.text().strip();
		Element pub = line.selectFirst("span.pub");
		if (pub != null) {
			pub.select("span.bar").remove();
		}
		Matcher goodsId = GOODS_ID.matcher(link.attr("href"));
		return ParsedItem.counted(title, HtmlParsing.textOrNull(line, "span.writer"),
				pub == null ? null : HtmlParsing.blankToNull(pub.text()), null, count(line, "span.volumecnt em"),
				count(line, "span.uselendingcnt em"), count(line, "span.usereservecnt em"),
				goodsId.find() ? goodsId.group(1) : null);
	}

	private static int count(Element line, String cssQuery) {
		Matcher m = NUMBER.matcher(HtmlParsing.required(line, cssQuery, SCREEN).text());
		if (!m.find()) {
			throw UpstreamException.error(SCREEN + ": " + cssQuery + " is not a number");
		}
		return HtmlParsing.number(m, 1);
	}

	/** The "보유현황" line: "보유 N, 대출 N, 예약 N" and on some sites ", 누적대출 N" after it. */
	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		for (Element label : doc.select("span.tt")) {
			if (label.text().strip().equals("보유현황")) {
				Element value = label.nextElementSibling();
				if (value == null) {
					break;
				}
				return HtmlParsing.detailCounts(value, SCREEN);
			}
		}
		throw UpstreamException.error(SCREEN + ": detail page has no 보유현황");
	}

}
