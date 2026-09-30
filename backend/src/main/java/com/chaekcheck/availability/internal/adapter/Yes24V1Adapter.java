package com.chaekcheck.availability.internal.adapter;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.library.PlatformType;

/**
 * The previous generation of the YES24 screen, in EUC-KR (e.g. {@code http://ebook.jeonju.go.kr:8095}). Same URL as
 * the current screen plus {@code view=10} (the site shows 5 rows unless asked; 5/10/20/50 are offered). The search
 * text must be EUC-KR, or the site finds nothing. The Content-Type header names no charset; pages declare EUC-KR in
 * a meta tag.
 * <pre>
 * #content                        "전체 <em>27</em>건 ( 1 /3 페이지)"
 * div#booklist ul.list > li.line  one result
 *   span.book_tit > a             title (span.book_kind before it is the category)
 *   ul.book_txt li[title=저자|출판사|공급사]
 *   div.rentinfo                  "보유 <em>1</em>, 대출 <em>0</em>, 예약 <em>0</em>, 누적대출 .."
 * </pre>
 */
@Component
public class Yes24V1Adapter implements PlatformAdapter {

	static final int PAGE_SIZE = 10;

	private static final Charset EUC_KR = Charset.forName("EUC-KR");

	private static final Pattern TOTAL = Pattern.compile("전체\\s*([\\d,]+)\\s*건");

	private static final Pattern STAT = Pattern.compile("(보유|대출|예약)\\s*([\\d,]+)");

	private final String sort;

	public Yes24V1Adapter(AvailabilityProperties properties) {
		this.sort = properties.yes24().sort();
	}

	@Override
	public PlatformType platform() {
		return PlatformType.YES24_V1;
	}

	@Override
	public int pageSize() {
		return PAGE_SIZE;
	}

	/** Detail page: "div.rentinfo" "보유 1, 대출 0, 예약 0, 누적대출 31, …" (호출 시험 2026-09-30). */
	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		return HtmlParsing.detailCounts(HtmlParsing.required(doc, "div.rentinfo", "YES24_V1"), "YES24_V1");
	}

	@Override
	public Charset charset() {
		return EUC_KR;
	}

	@Override
	public URI searchUri(String baseUrl, SearchQuery query, int page) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/search/")
			.queryParam("src_key", HtmlParsing.encode(query.value(), EUC_KR))
			.queryParam("srch_order", Yes24V2Adapter.searchOrder(query.field()))
			.queryParam("sort", sort)
			.queryParam("view", PAGE_SIZE)
			.queryParam("page_num", page)
			.build(true)
			.toUri();
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		Element content = HtmlParsing.required(doc, "#content", "YES24_V1");
		Matcher total = TOTAL.matcher(content.text());
		if (!total.find()) {
			throw UpstreamException.error("YES24_V1: total count not found");
		}
		List<ParsedItem> items = new ArrayList<>();
		for (Element li : content.select("div#booklist ul.list > li.line")) {
			items.add(item(li));
		}
		return new ParsedPage(HtmlParsing.number(total, 1), items);
	}

	private static ParsedItem item(Element li) {
		Element titleLink = HtmlParsing.required(li, "span.book_tit > a", "YES24_V1");
		String title = titleLink.text().strip();
		Map<String, Integer> stats = new HashMap<>();
		Matcher m = STAT.matcher(HtmlParsing.required(li, "div.rentinfo", "YES24_V1").text());
		while (m.find()) {
			stats.putIfAbsent(m.group(1), HtmlParsing.number(m, 2));
		}
		if (!stats.keySet().containsAll(List.of("보유", "대출", "예약"))) {
			throw UpstreamException.error("YES24_V1: rentinfo is missing 보유/대출/예약 for " + title);
		}
		return ParsedItem.counted(title, author(li), HtmlParsing.textOrNull(li, "ul.book_txt li[title=출판사]"),
				HtmlParsing.textOrNull(li, "ul.book_txt li[title=공급사]"), stats.get("보유"), stats.get("대출"),
				stats.get("예약"), Yes24V2Adapter.detailPath(titleLink));
	}

	/** "<최제현> 저" -> "최제현 저": the site wraps each name in angle brackets. */
	private static String author(Element li) {
		String text = HtmlParsing.textOrNull(li, "ul.book_txt li[title=저자]");
		return text == null ? null : HtmlParsing.blankToNull(text.replaceAll("[<>]", ""));
	}

}
