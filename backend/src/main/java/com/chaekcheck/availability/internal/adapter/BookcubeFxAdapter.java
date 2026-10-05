package com.chaekcheck.availability.internal.adapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.library.PlatformType;

/**
 * Bookcube FxLibrary site ({@code base_url} ends in {@code /FxLibrary}, {@code /FxLibrary_gj}, {@code /BookLive} …),
 * read through the site's own XML (ADR-0056, plan research 1절, 호출 시험 2026-10-05):
 * <pre>
 * {base}/xml/productSearch/?keyword=..&amp;keyoption2=1|2|3&amp;itemCount=10&amp;page=N   search (title, author, publisher)
 * {base}/xml/productView/?num=..                                         one book (its page is /product/view/?num=)
 * Products/ProductListParam/totalItem   total count
 * Product/num, name, author, publisher, supplier
 * Product/volume, nowLendCount, nowReserveCount     holdings, loaned, reservations
 * Product/nowRentalCount                            only on rental sites: then volume is 0 and the title is a
 *                                                   subscription ("구독형 · 바로 대출")
 * </pre>
 * Only the elements right under {@code <Product>} are read ({@code <Terminals>} has more {@code <name>}s). The XML
 * parser takes no DTD and no external entities. A missing book answers an empty document: an error.
 */
@Component
public class BookcubeFxAdapter implements PlatformAdapter {

	static final int PAGE_SIZE = 10;

	private static final String SCREEN = "BOOKCUBE_FX";

	@Override
	public PlatformType platform() {
		return PlatformType.BOOKCUBE_FX;
	}

	@Override
	public int pageSize() {
		return PAGE_SIZE;
	}

	@Override
	public URI searchUri(String baseUrl, SearchQuery query, int page) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/xml/productSearch/")
			.queryParam("keyword", query.value())
			.queryParam("keyoption2", keyOption(query.field()))
			.queryParam("itemCount", PAGE_SIZE)
			.queryParam("page", page)
			.encode()
			.build()
			.toUri();
	}

	/** {@code keyoption2}: 1 title, 2 author, 3 publisher (호출 시험 2026-10-05: 김영하, 문학동네). */
	static int keyOption(SearchField field) {
		return switch (field) {
			case TITLE -> 1;
			case AUTHOR -> 2;
			case PUBLISHER -> 3;
		};
	}

	/** "도서관에서 보기": the XML is not for people, so the site's front page. */
	@Override
	public URI librarySearchUri(String baseUrl, SearchQuery query, URI fetchedUri) {
		return URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
	}

	/** Search {@code …/xml/productSearch/} → the book's page {@code …/product/view/?num=}. */
	@Override
	public URI detailUri(URI searchUri, String detailPath) {
		return searchUri.resolve("../../" + detailPath);
	}

	/** The book's page {@code …/product/view/?num=} is read as {@code …/xml/productView/?num=}. */
	@Override
	public URI detailFetchUri(URI detailUri) {
		String raw = detailUri.toString();
		int at = raw.indexOf("/product/view/");
		return at < 0 ? detailUri
				: URI.create(raw.substring(0, at) + "/xml/productView/" + raw.substring(at + "/product/view/".length()));
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		Document doc = xml(body);
		Element root = doc.getDocumentElement();
		if (root == null || !root.getTagName().equals("Products")) {
			throw UpstreamException.error(SCREEN + ": search answer is not <Products>");
		}
		Element param = child(root, "ProductListParam");
		String total = param == null ? null : text(param, "totalItem");
		if (total == null || !total.matches("\\d+")) {
			throw UpstreamException.error(SCREEN + ": totalItem missing");
		}
		List<ParsedItem> items = new ArrayList<>();
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element product && product.getTagName().equals("Product")) {
				items.add(item(product));
			}
		}
		return new ParsedPage(Integer.parseInt(total), items);
	}

	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		Element product = xml(body).getDocumentElement();
		if (product == null || !product.getTagName().equals("Product")) {
			throw UpstreamException.error(SCREEN + ": detail answer is not <Product> (no such book?)");
		}
		Counts counts = counts(product);
		return new ParsedDetail(counts.holdings, counts.loaned, counts.reservations,
				counts.rental ? SiteVerdict.SUBSCRIPTION : null);
	}

	private static ParsedItem item(Element product) {
		String title = text(product, "name");
		String num = text(product, "num");
		if (title == null || num == null) {
			throw UpstreamException.error(SCREEN + ": product without name or num");
		}
		Counts counts = counts(product);
		return new ParsedItem(title, text(product, "author"), text(product, "publisher"), text(product, "supplier"),
				counts.holdings, counts.loaned, counts.reservations,
				UriComponentsBuilder.fromPath("product/view/").queryParam("num", num).encode().build().toUriString(),
				counts.rental ? SiteVerdict.SUBSCRIPTION : null);
	}

	private record Counts(int holdings, int loaned, int reservations, boolean rental) {
	}

	/** A rental title: no copies ({@code volume} 0) and a {@code nowRentalCount} element (부산 구독, 2026-10-05). */
	private static Counts counts(Element product) {
		int holdings = number(product, "volume");
		return new Counts(holdings, number(product, "nowLendCount"), number(product, "nowReserveCount"),
				holdings == 0 && child(product, "nowRentalCount") != null);
	}

	private static int number(Element parent, String tag) {
		String value = text(parent, tag);
		if (value == null || !value.matches("\\d+")) {
			throw UpstreamException.error(SCREEN + ": " + tag + " is not a number");
		}
		return Integer.parseInt(value);
	}

	/** Text of a direct child, CDATA included; null when missing or blank. */
	private static String text(Element parent, String tag) {
		Element child = child(parent, tag);
		return child == null ? null : HtmlParsing.blankToNull(child.getTextContent());
	}

	private static Element child(Element parent, String tag) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (node instanceof Element element && element.getTagName().equals(tag)) {
				return element;
			}
		}
		return null;
	}

	/** The JDK parser with DTDs, external entities and XInclude off (plan design-delta 외부 연동). */
	static Document xml(byte[] body) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			DocumentBuilder builder = factory.newDocumentBuilder();
			builder.setErrorHandler(null);
			return builder.parse(new ByteArrayInputStream(body));
		}
		catch (ParserConfigurationException ex) {
			throw new IllegalStateException(ex);
		}
		catch (SAXException | IOException ex) {
			throw new UpstreamException(UpstreamException.Kind.ERROR, SCREEN + ": not readable XML", ex);
		}
	}

}
