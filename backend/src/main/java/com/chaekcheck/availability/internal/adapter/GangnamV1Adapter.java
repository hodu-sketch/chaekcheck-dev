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

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.library.PlatformType;

/**
 * Gangnam-gu's own e-library system ({@code https://ebook.gangnam.go.kr}, EUC-KR ASP). The search text must be
 * EUC-KR. Pages are 20 rows: the site ignores {@code list_num=10} (checked 2026-09-28), so the site's size is used.
 * <pre>
 * div.list_header strong          total count ("도서 검색 결과가 없습니다." when none)
 * div.book_info                   one result (the list is also wrapped in a div.book)
 *   div.book_title a              title, links to book_detail.asp?book_num=..
 *   div.writer                    author
 *   div.publish_date              "publisher · date"
 *   div.current strong            supplier (first div.current)
 *   div.current span              "보유 N", "대출 N", "예약 N"
 * </pre>
 */
@Component
public class GangnamV1Adapter implements PlatformAdapter {

	/** Fixed by the site. */
	static final int PAGE_SIZE = 20;

	private static final Charset EUC_KR = Charset.forName("EUC-KR");

	private static final Pattern NUMBER = Pattern.compile("([\\d,]+)");

	private static final Pattern STAT = Pattern.compile("(보유|대출|예약)\\s*([\\d,]+)");

	@Override
	public PlatformType platform() {
		return PlatformType.GANGNAM_V1;
	}

	@Override
	public int pageSize() {
		return PAGE_SIZE;
	}

	@Override
	public Charset charset() {
		return EUC_KR;
	}

	/**
	 * Detail page ({@code book_detail.asp}): the book's own {@code div.current} is the first one in
	 * {@code div.book_warp}; the related books below ({@code div.book_list}) have their own blocks, never read
	 * (호출 시험 2026-09-30). A {@code div.current.close} notice ("예약정원이 마감되었습니다.") has no counts.
	 */
	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		Element book = HtmlParsing.required(doc, "div.book_warp", "GANGNAM_V1");
		Element current = book.select("div.current")
			.stream()
			.filter(element -> element.closest("div.book_list") == null)
			.findFirst()
			.orElseThrow(() -> UpstreamException.error("GANGNAM_V1: detail page has no book block"));
		if (current.hasClass("close")) {
			return new ParsedDetail(null, null, null, SiteVerdict.OTHER);
		}
		return HtmlParsing.detailCounts(current, "GANGNAM_V1");
	}

	@Override
	public URI searchUri(String baseUrl, SearchQuery query, int page) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/elibbook/book_info.asp")
			.queryParam("search", searchField(query.field()))
			.queryParam("strSearch", HtmlParsing.encode(query.value(), EUC_KR))
			.queryParam("page_num", page)
			.build(true)
			.toUri();
	}

	private static String searchField(SearchField field) {
		return switch (field) {
			case TITLE -> "title";
			case AUTHOR -> "author";
			case PUBLISHER -> "publisher";
		};
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		List<Element> books = doc.select("div.book_info:has(> div.book_title)");
		if (books.isEmpty()) {
			if (doc.text().contains("검색 결과가 없습니다")) {
				return new ParsedPage(0, List.of());
			}
			throw UpstreamException.error("GANGNAM_V1: no results and no empty-result text");
		}
		Integer total = total(doc);
		if (total == null) {
			throw UpstreamException.error("GANGNAM_V1: total count not found");
		}
		List<ParsedItem> items = new ArrayList<>();
		for (Element book : books) {
			items.add(item(book));
		}
		return new ParsedPage(total, items);
	}

	/** {@code div.list_header}: "<span>도서명 : 사랑</span> <strong>424</strong>종". */
	private static Integer total(Document doc) {
		Element count = doc.selectFirst("div.list_header strong");
		if (count == null) {
			return null;
		}
		Matcher m = NUMBER.matcher(count.text());
		return m.matches() ? HtmlParsing.number(m, 1) : null;
	}

	private static ParsedItem item(Element book) {
		Element titleLink = HtmlParsing.required(book, "div.book_title a", "GANGNAM_V1");
		String title = titleLink.text().strip();
		Map<String, Integer> stats = new HashMap<>();
		String supplier = HtmlParsing.textOrNull(book, "div.current:not(.close) strong");
		for (Element current : book.select("div.current:not(.close)")) {
			Matcher m = STAT.matcher(current.text());
			while (m.find()) {
				stats.put(m.group(1), HtmlParsing.number(m, 2));
			}
		}
		String publishLine = HtmlParsing.textOrNull(book, "div.publish_date");
		String publisher = publishLine == null ? null : HtmlParsing.blankToNull(publishLine.split("·")[0]);
		String href = titleLink.attr("href");
		String author = HtmlParsing.textOrNull(book, "div.writer");
		String detailPath = href.isBlank() ? null : href;
		if (book.selectFirst("div.current.close") != null) {
			// "예약정원이 마감되었습니다.": the counts are replaced by this notice; cannot borrow or reserve now
			return new ParsedItem(title, author, publisher, supplier, null, null, null, detailPath, SiteVerdict.OTHER);
		}
		if (!stats.keySet().containsAll(List.of("보유", "대출", "예약"))) {
			throw UpstreamException.error("GANGNAM_V1: missing 보유/대출/예약 for " + title);
		}
		return ParsedItem.counted(title, author, publisher, supplier, stats.get("보유"), stats.get("대출"),
				stats.get("예약"), detailPath);
	}

}
