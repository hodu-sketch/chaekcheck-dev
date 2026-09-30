package com.chaekcheck.availability.internal.adapter;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.chaekcheck.availability.internal.UpstreamException;

final class HtmlParsing {

	private HtmlParsing() {
	}

	static Document document(byte[] body, Charset charset) {
		return Jsoup.parse(new String(body, charset));
	}

	/**
	 * Percent-encodes a query value in the site's charset (EUC-KR sites need EUC-KR bytes). Spaces become
	 * {@code %20}. The result goes into {@code UriComponentsBuilder.build(true)} as an already encoded value.
	 */
	static String encode(String value, Charset charset) {
		return URLEncoder.encode(value, charset).replace("+", "%20");
	}

	static Element required(Element parent, String cssQuery, String platform) {
		Element element = parent.selectFirst(cssQuery);
		if (element == null) {
			throw UpstreamException.error(platform + ": missing " + cssQuery);
		}
		return element;
	}

	static String textOrNull(Element parent, String cssQuery) {
		Element element = parent.selectFirst(cssQuery);
		return element == null || element.text().isBlank() ? null : element.text().strip();
	}

	static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

	static int number(Matcher matcher, int group) {
		return Integer.parseInt(matcher.group(group).replace(",", ""));
	}

	/**
	 * Counts of a detail page block "보유 N ... 대출 N ... 예약 N" (YES24, Gangnam). The first number after each word
	 * counts; "대출예정일" or "누적대출" later in the block do not match or come after.
	 * @throws UpstreamException (ERROR) when one of the three is missing
	 */
	static ParsedDetail detailCounts(Element block, String screen) {
		Map<String, Integer> stats = new HashMap<>();
		Matcher m = DETAIL_STAT.matcher(block.text());
		while (m.find()) {
			stats.putIfAbsent(m.group(1), number(m, 2));
		}
		if (!stats.keySet().containsAll(List.of("보유", "대출", "예약"))) {
			throw UpstreamException.error(screen + ": detail page is missing 보유/대출/예약");
		}
		return ParsedDetail.counted(stats.get("보유"), stats.get("대출"), stats.get("예약"));
	}

	private static final Pattern DETAIL_STAT = Pattern.compile("(보유|대출|예약)\\s*([\\d,]+)");

}
