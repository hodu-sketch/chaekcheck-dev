package com.chaekcheck.availability.internal.adapter;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;

/**
 * The KYOBO search screen, shared by regular sites ({@code {origin}/elibrary-front/search/searchList.ink}) and
 * subscription sites ({@code {origin}/search/searchList.ink}, no counts). Same markup otherwise:
 * <pre>
 * div.book_resultTxt              "총 103개" (or "검색 결과가 없습니다")
 * ul.book_resultList > li         one result
 *   li.tit a                      title
 *   li.writer                     "author<span>publisher</span>date"
 *   span.store                    supplier, read per row (one page can mix suppliers)
 *   p.use                         "대출 : X/Y  예약 : Z" = loaned X, holdings Y, reservations Z (regular sites only)
 * </pre>
 */
abstract class KyoboV1ScreenAdapter implements PlatformAdapter {

	private static final Pattern TOTAL = Pattern.compile("총\\s*([\\d,]+)\\s*개");

	private static final Pattern USE = Pattern
		.compile("대출\\s*:\\s*([\\d,]+)\\s*/\\s*([\\d,]+).*?예약\\s*:\\s*([\\d,]+)");

	private static final Pattern CONTENT_CLICK = Pattern
		.compile("fnContentClick\\(\\s*this\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)'");

	private final int recordCount;

	KyoboV1ScreenAdapter(int recordCount) {
		this.recordCount = recordCount;
	}

	/** Path between the origin and {@code /search/searchList.ink}. */
	abstract String pathPrefix();

	/**
	 * Whether every row shows "대출 : X/Y 예약 : Z". When false (subscription screen) a row may still show it: an
	 * owned title of an institution with owned copies (2026-10-05 호출 시험: 거제시청, 대구광역시교육청).
	 */
	abstract boolean hasCounts();

	/**
	 * Detail page ({@code contentView.ink}): one {@code span.use} "[ 대출 : X/Y 예약 : Z ]" for the book (호출 시험
	 * 2026-09-30; the subscription screen of an owned-copies institution too, 2026-10-05). Subscription titles'
	 * detail pages are never requested: only libraries with {@code lendingModel = OWNED} read them (ADR-0056).
	 */
	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		Element use = HtmlParsing.required(doc, "span.use", platform().name());
		Matcher m = USE.matcher(use.text());
		if (!m.find()) {
			throw UpstreamException.error(platform() + ": detail span.use has no counts");
		}
		return ParsedDetail.counted(HtmlParsing.number(m, 2), HtmlParsing.number(m, 1), HtmlParsing.number(m, 3));
	}

	@Override
	public int pageSize() {
		return recordCount;
	}

	@Override
	public URI searchUri(String baseUrl, SearchQuery query, int page) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path(pathPrefix() + "/search/searchList.ink")
			.queryParam("schClst", searchClass(query.field()))
			.queryParam("schDvsn", "000")
			.queryParam("schTxt", query.value())
			.queryParam("recordCount", recordCount)
			.queryParam("pageIndex", page)
			.encode()
			.build()
			.toUri();
	}

	/** Values of the site's own "schClst" select ("all" would search every field). */
	private static String searchClass(SearchField field) {
		return switch (field) {
			case TITLE -> "ctts";
			case AUTHOR -> "autr";
			case PUBLISHER -> "pbcm";
		};
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		Document doc = HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset()));
		String summary = HtmlParsing.required(doc, "div.book_resultTxt", platform().name()).text();
		Matcher total = TOTAL.matcher(summary);
		if (!total.find()) {
			if (summary.contains("검색 결과가 없습니다")) {
				return new ParsedPage(0, List.of());
			}
			throw UpstreamException.error(platform() + ": total count not found");
		}
		List<ParsedItem> items = new ArrayList<>();
		for (Element li : doc.select("ul.book_resultList > li")) {
			items.add(item(li));
		}
		return new ParsedPage(HtmlParsing.number(total, 1), items);
	}

	private ParsedItem item(Element li) {
		String title = HtmlParsing.required(li, "li.tit a", platform().name()).text().strip();
		Element writer = li.selectFirst("li.writer");
		String author = writer == null ? null : author(writer);
		String publisher = writer == null ? null : HtmlParsing.textOrNull(writer, "span");
		String supplier = HtmlParsing.textOrNull(li, "span.store");
		Element useLine = hasCounts() ? HtmlParsing.required(li, "p.use", platform().name()) : li.selectFirst("p.use");
		if (useLine == null) {
			return new ParsedItem(title, author, publisher, supplier, null, null, null, detailPath(li), null);
		}
		Matcher use = USE.matcher(useLine.text());
		if (!use.find()) {
			throw UpstreamException.error(platform() + ": unexpected p.use text for " + title);
		}
		return ParsedItem.counted(title, author, publisher, supplier, HtmlParsing.number(use, 2),
				HtmlParsing.number(use, 1), HtmlParsing.number(use, 3), detailPath(li));
	}

	/**
	 * The title calls {@code searchList.fnContentClick(this, cttsDvsnCode, brcd, ctgrId, sntnAuthCode, ...)}, which
	 * opens contentView.ink with those four values. The same URL opens the book page directly (checked 2026-09-27).
	 */
	private String detailPath(Element li) {
		Element link = li.selectFirst("li.tit a[onclick]");
		Matcher click = link == null ? null : CONTENT_CLICK.matcher(link.attr("onclick"));
		if (click == null || !click.find()) {
			return null;
		}
		return UriComponentsBuilder.fromPath(pathPrefix() + "/content/contentView.ink")
			.queryParam("cttsDvsnCode", click.group(1))
			.queryParam("brcd", click.group(2))
			.queryParam("ctgrId", click.group(3))
			.queryParam("sntnAuthCode", click.group(4))
			.encode()
			.build()
			.toUriString();
	}

	/** The text before the publisher span; the text after it is the publish date. */
	private static String author(Element writer) {
		StringBuilder text = new StringBuilder();
		for (Node node : writer.childNodes()) {
			if (node instanceof Element) {
				break;
			}
			if (node instanceof TextNode textNode) {
				text.append(textNode.text());
			}
		}
		return text.toString().isBlank() ? null : text.toString().strip();
	}

}
