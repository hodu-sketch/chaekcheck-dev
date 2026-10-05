package com.chaekcheck.availability.internal.adapter;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.library.PlatformType;

/**
 * One kind of e-library search screen (ADR-0016): builds the search URL and reads what the page shows. Not an
 * official API. An adapter only says what is written on the screen; whether a book can be borrowed is decided by
 * an {@code AvailabilityResolver} (ADR-0019).
 */
public interface PlatformAdapter {

	PlatformType platform();

	/** Rows per page; requested (10) where the site allows it, otherwise the site's fixed size. */
	int pageSize();

	/** Charset of the site's pages and of the search text in the URL. */
	default Charset charset() {
		return StandardCharsets.UTF_8;
	}

	/** Search URL for the query's field. The adapter encodes the text in {@link #charset()}. */
	URI searchUri(String baseUrl, SearchQuery query, int page);

	/**
	 * The page "도서관에서 보기" opens ({@code librarySearchUrl}): by default the search page that was fetched.
	 * @param fetchedUri the {@link #searchUri} the page was fetched from
	 */
	default URI librarySearchUri(String baseUrl, SearchQuery query, URI fetchedUri) {
		return fetchedUri;
	}

	/**
	 * @param declaredCharset charset from the Content-Type header, or null; {@link #charset()} is used when null
	 * @throws com.chaekcheck.availability.internal.UpstreamException (ERROR) when the page does not have the
	 * expected structure
	 */
	ParsedPage parse(byte[] body, Charset declaredCharset);

	/**
	 * Reads a book's detail page (ADR-0029): counts on count screens, the site's verdict on the portal. Used by the
	 * holding stream and by the portal's blank-verdict lookup.
	 * @throws com.chaekcheck.availability.internal.UpstreamException (ERROR) when the page does not have the
	 * expected structure
	 * @throws UnsupportedOperationException for screens whose detail pages are never requested (subscription)
	 */
	default ParsedDetail parseDetail(byte[] body, Charset declaredCharset) {
		throw new UnsupportedOperationException(platform() + " detail pages are not read");
	}

	/**
	 * What is actually requested for a book's page (cycle 07): by default the page itself. Must stay on the same
	 * host, so the throttle and the cache key do not change. Bookcube reads the same book as XML.
	 */
	default URI detailFetchUri(URI detailUri) {
		return detailUri;
	}

	/** The book's page on the site for a parsed detail path, relative to the search page it came from. */
	default URI detailUri(URI searchUri, String detailPath) {
		return searchUri.resolve(detailPath);
	}

	static Charset charsetOr(Charset declared, Charset fallback) {
		return declared != null ? declared : fallback;
	}

}
