package com.chaekcheck.availability.internal.adapter;

import java.net.URI;
import java.nio.charset.Charset;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.library.PlatformType;

/**
 * The current YES24 screen layout served in EUC-KR (e.g. {@code http://ebook.gcl.go.kr}, 김천·경주·구미·마포나루,
 * checked 2026-09-29). The page is the same as {@link Yes24V2Adapter}'s, but the search text must be EUC-KR: a UTF-8
 * query always finds 0. Pages declare EUC-KR in a meta tag.
 */
@Component
public class Yes24V3Adapter implements PlatformAdapter {

	private static final Charset EUC_KR = Charset.forName("EUC-KR");

	private final String sort;

	public Yes24V3Adapter(AvailabilityProperties properties) {
		this.sort = properties.yes24().sort();
	}

	@Override
	public PlatformType platform() {
		return PlatformType.YES24_V3;
	}

	@Override
	public int pageSize() {
		return Yes24V2Adapter.PAGE_SIZE;
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
			.queryParam("page_num", page)
			.queryParam("sort", sort)
			.build(true)
			.toUri();
	}

	@Override
	public ParsedPage parse(byte[] body, Charset declaredCharset) {
		return Yes24V2Adapter.parseDocument(
				HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset())), "YES24_V3");
	}

	@Override
	public ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		return Yes24V2Adapter.parseDetailDocument(
				HtmlParsing.document(body, PlatformAdapter.charsetOr(declaredCharset, charset())), "YES24_V3");
	}

}
