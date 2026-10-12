package com.chaekcheck.picks.internal.fetch;

import java.io.ByteArrayInputStream;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.chaekcheck.catalog.normalize.Isbn;

/**
 * Reads the answer of {@code saseoApi.do} (plan research alternatives.md 3-0절: {@code <channel><totalCount/><list>
 * <item>…}, item names {@code recomNo}, {@code drCode}, {@code drCodeName}, {@code recomtitle}, {@code recomauthor},
 * {@code recompublisher}, {@code recomisbn}, {@code recomYear}, {@code recomMonth}, {@code regdate},
 * {@code recomfilepath}). The answer is untrusted input (plan cycle 14 C6): DTDs and external entities are off, only
 * text values are read, and the contents and recommendation texts ({@code recommokcha}, {@code recomcontens}, HTML)
 * are never kept.
 * <p>
 * Failures [안]: no {@code <channel>} or {@code <totalCount>} is {@code format_changed}, or {@code request_rejected}
 * when an error code element is there instead (the API's error answer has not been seen yet [확인 필요]); one item
 * with a {@code recomNo} that is not 1 to 20 digits, an empty title or field code, or a month that cannot be read is
 * {@code format_changed}; {@code totalCount} larger than the items is {@code incomplete}. An ISBN that cannot be read
 * is kept as NULL, a {@code regdate} that cannot be read as NULL, a cover address that is not allowed as NULL.
 */
public final class SaseoXmlParser {

	private static final Pattern RECOM_NO = Pattern.compile("[0-9]{1,20}");

	private static final Pattern REGDATE = Pattern.compile("(\\d{4})[.\\-](\\d{1,2})[.\\-](\\d{1,2})\\.?");

	/** Names an error answer may use [확인 필요: never seen]. */
	private static final Pattern ERROR_ELEMENT = Pattern.compile("(?i).*error.*|err_?code|result_?code|result_?msg");

	/** Elements whose text is an error code. */
	private static final Pattern CODE_ELEMENT = Pattern.compile("(?i)(error_?|err_?|result_?)code");

	/** A code shown in failure_detail: short letters and digits only, never free text. */
	private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_\\-]{1,20}");

	private static final XMLInputFactory FACTORY = factory();

	/** The list as received: {@code totalCount} and every item in answer order (a repeated recomNo kept once). */
	public record SaseoList(int totalCount, List<PickRow> rows) {
	}

	private SaseoXmlParser() {
	}

	public static SaseoList parse(byte[] body) {
		try {
			XMLStreamReader reader = FACTORY.createXMLStreamReader(new ByteArrayInputStream(body));
			try {
				return read(reader);
			}
			finally {
				reader.close();
			}
		}
		catch (XMLStreamException ex) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, "not readable XML");
		}
	}

	private static SaseoList read(XMLStreamReader reader) throws XMLStreamException {
		boolean channel = false;
		Integer totalCount = null;
		String errorCode = null;
		boolean errorElement = false;
		List<PickRow> rows = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		int itemNumber = 0;
		while (reader.hasNext()) {
			if (reader.next() != XMLStreamConstants.START_ELEMENT) {
				continue;
			}
			String name = reader.getLocalName();
			switch (name) {
				case "channel" -> channel = true;
				case "totalCount" -> totalCount = count(text(reader));
				case "item" -> {
					itemNumber++;
					PickRow row = row(item(reader), itemNumber);
					if (seen.add(row.recomNo())) {
						rows.add(row);
					}
				}
				default -> {
					if (ERROR_ELEMENT.matcher(name).matches()) {
						errorElement = true;
					}
					if (CODE_ELEMENT.matcher(name).matches()) {
						String value = text(reader).strip();
						if (errorCode == null && SAFE_CODE.matcher(value).matches()) {
							errorCode = value;
						}
					}
				}
			}
		}
		if (!channel || totalCount == null) {
			if (errorElement) {
				throw new FetchFailure(FailureReason.REQUEST_REJECTED,
						"API error" + (errorCode == null ? "" : " " + errorCode));
			}
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, channel ? "no totalCount" : "no channel");
		}
		if (totalCount > itemNumber) {
			throw new FetchFailure(FailureReason.INCOMPLETE, "totalCount " + totalCount + ", items " + itemNumber);
		}
		return new SaseoList(totalCount, List.copyOf(rows));
	}

	/** The text values of one item's child elements, by name; nested markup is read as its text. */
	private static Map<String, String> item(XMLStreamReader reader) throws XMLStreamException {
		Map<String, String> values = new HashMap<>();
		while (reader.hasNext()) {
			int event = reader.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				String name = reader.getLocalName();
				String value = text(reader);
				if (!name.equals("recommokcha") && !name.equals("recomcontens")) {
					values.put(name, value);
				}
			}
			else if (event == XMLStreamConstants.END_ELEMENT) {
				return values;
			}
		}
		throw new XMLStreamException("item not closed");
	}

	/** All text up to the end of the current element, nested elements included. */
	private static String text(XMLStreamReader reader) throws XMLStreamException {
		StringBuilder text = new StringBuilder();
		int depth = 1;
		while (depth > 0) {
			int event = reader.next();
			switch (event) {
				case XMLStreamConstants.START_ELEMENT -> depth++;
				case XMLStreamConstants.END_ELEMENT -> depth--;
				case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
					text.append(reader.getText());
				case XMLStreamConstants.ENTITY_REFERENCE ->
					throw new XMLStreamException("entity reference " + reader.getLocalName());
				case XMLStreamConstants.END_DOCUMENT -> throw new XMLStreamException("document ended");
				default -> {
				}
			}
		}
		return text.toString();
	}

	private static int count(String raw) {
		try {
			return Integer.parseInt(raw.strip());
		}
		catch (NumberFormatException ex) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, "totalCount");
		}
	}

	private static PickRow row(Map<String, String> item, int number) {
		String recomNo = blankToNull(item.get("recomNo"));
		if (recomNo == null || !RECOM_NO.matcher(recomNo).matches()) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, "item " + number + ": recomNo");
		}
		String drCode = blankToNull(item.get("drCode"));
		if (drCode == null || drCode.length() > 20) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, "item " + number + ": drCode");
		}
		String title = blankToNull(item.get("recomtitle"));
		if (title == null) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, "item " + number + ": recomtitle");
		}
		LocalDate month = month(item.get("recomYear"), item.get("recomMonth"));
		if (month == null) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, "item " + number + ": recomYear/recomMonth");
		}
		return new PickRow(recomNo, drCode, blankToNull(item.get("drCodeName")), month, regdate(item.get("regdate")),
				title, blankToNull(item.get("recomauthor")), blankToNull(item.get("recompublisher")),
				Isbn.toIsbn13(blankToNull(item.get("recomisbn"))),
				CoverUrls.normalize(item.get("recomfilepath")).orElse(null));
	}

	private static LocalDate month(String year, String month) {
		try {
			return LocalDate.of(Integer.parseInt(year.strip()), Integer.parseInt(month.strip()), 1);
		}
		catch (NullPointerException | NumberFormatException | DateTimeException ex) {
			return null;
		}
	}

	private static LocalDate regdate(String raw) {
		if (raw == null) {
			return null;
		}
		Matcher m = REGDATE.matcher(raw.strip());
		if (!m.matches()) {
			return null;
		}
		try {
			return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
		}
		catch (DateTimeException ex) {
			return null;
		}
	}

	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}

	private static XMLInputFactory factory() {
		XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		factory.setProperty(XMLInputFactory.IS_COALESCING, true);
		return factory;
	}

}
