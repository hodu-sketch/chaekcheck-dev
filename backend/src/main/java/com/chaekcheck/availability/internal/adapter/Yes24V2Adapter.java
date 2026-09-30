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
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.library.PlatformType;

/**
 * YES24 e-library search page, e.g.
 * {@code https://ebook.gangseo.seoul.kr/search/?src_key=..&srch_order=title&page_num=N&sort=title}.
 * Note: {@code sort=now} is a filter (only books available now), not an order, so the default is {@code title}.
 * <pre>
 * div.total em                    total count ("전체 <em>221</em>건")
 * div.ebook-list div.bx           one result
 *   p.tit a / p.writer            title / author
 *   p.detail span[0], span[2]     publisher / supplier (span[1] is the publish date)
 *   div.stat li                   "보유 N", "대출 N", "예약 N"
 * </pre>
 */
@Component
public class Yes24V2Adapter implements PlatformAdapter {

	/** Fixed by the site. */
	static final int PAGE_SIZE = 10;

	private static final Pattern NUMBER = Pattern.compile("([\\d,]+)");

	private static final Pattern STAT = Pattern.compile("(보유|대출|예약)\\s*([\\d,]+)");

	private static final Pattern GOODS_ID = Pattern.compile("goods_id=(\\d+)");

	private final String sort;

	public Yes24V2Adapter(AvailabilityProperties properties) {
		this.sort = properties.yes24().sort();
	}

	@Override
	public PlatformType platform() {
		return PlatformType.YES24_V2;
	}

	@Override
	public int pageSize() {
		return PAGE_SIZE;
	}

	/** Detail page: "div.stat" "보유 N 대출 N 예약 N" (호출 시험 2026-09-30); same markup on YES24_V3. */
	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		return parseDetailDocument(HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset())),
				"YES24_V2");
	}

	static ParsedDetail parseDetailDocument(Document doc, String screen) {
		return HtmlParsing.detailCounts(HtmlParsing.required(doc, "div.stat", screen), screen);
	}

	@Override
	public URI searchUri(String baseUrl, SearchQuery query, int page) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/search/")
			.queryParam("src_key", query.value())
			.queryParam("srch_order", searchOrder(query.field()))
			.queryParam("page_num", page)
			.queryParam("sort", sort)
			.encode()
			.build()
			.toUri();
	}

	/** Values of the site's own "srch_order" select (empty or "total" would search every field). */
	static String searchOrder(SearchField field) {
		return switch (field) {
			case TITLE -> "title";
			case AUTHOR -> "author";
			case PUBLISHER -> "publisher";
		};
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		return parseDocument(HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset())),
				"YES24_V2");
	}

	/** The result list of this screen layout; {@link Yes24V3Adapter} has the same layout in EUC-KR. */
	static ParsedPage parseDocument(Document doc, String screen) {
		Matcher total = NUMBER.matcher(HtmlParsing.required(doc, "div.total em", screen).text());
		if (!total.find()) {
			throw UpstreamException.error(screen + ": total count is not a number");
		}
		List<ParsedItem> items = new ArrayList<>();
		for (Element bx : doc.select("div.ebook-list div.bx")) {
			items.add(item(bx, screen));
		}
		return new ParsedPage(HtmlParsing.number(total, 1), items);
	}

	private static ParsedItem item(Element bx, String screen) {
		String title = HtmlParsing.required(bx, "p.tit a", screen).text().strip();
		Elements details = bx.select("p.detail span");
		String publisher = details.size() > 0 ? HtmlParsing.blankToNull(details.get(0).text()) : null;
		String supplier = details.size() > 2 ? HtmlParsing.blankToNull(details.get(2).text()) : null;
		Map<String, Integer> stats = new HashMap<>();
		for (Element li : HtmlParsing.required(bx, "div.stat", screen).select("li")) {
			Matcher m = STAT.matcher(li.text());
			if (m.find()) {
				stats.put(m.group(1), HtmlParsing.number(m, 2));
			}
		}
		if (!stats.keySet().containsAll(List.of("보유", "대출", "예약"))) {
			throw UpstreamException.error(screen + ": stat is missing 보유/대출/예약 for " + title);
		}
		return ParsedItem.counted(title, HtmlParsing.textOrNull(bx, "p.writer"), publisher, supplier, stats.get("보유"),
				stats.get("대출"), stats.get("예약"), detailPath(bx.selectFirst("p.tit a[href]")));
	}

	/** The title links to /ebook/detail?goods_id=N, which redirects to /ebook/detail/?goods_id=N (both YES24 screens). */
	static String detailPath(Element link) {
		Matcher goodsId = link == null ? null : GOODS_ID.matcher(link.attr("href"));
		return goodsId != null && goodsId.find() ? "/ebook/detail/?goods_id=" + goodsId.group(1) : null;
	}

}
