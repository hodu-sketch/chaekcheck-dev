package com.chaekcheck.availability.internal.ranked;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.availability.RankedListings;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.ParsedItem;
import com.chaekcheck.availability.internal.adapter.ParsedPage;
import com.chaekcheck.availability.internal.adapter.PlatformAdapter;
import com.chaekcheck.availability.internal.egress.EgressLanes;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.fetch.SiteHostNames;
import com.chaekcheck.availability.internal.fetch.SiteResponse;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

/**
 * Reads a popular-order page with the search screens' own parsers: the KYOBO content list has the search page's markup
 * and the YES24 V1 lending order is the search page itself (cycle 23 step 0 #2). Goes straight to {@link EgressLanes}
 * (lanes, per-lane host interval, blocked lanes, egress mode), not through the {@code HostQueueBoard} or the search page
 * cache, so the lookup status signal does not see these requests; nothing is written to the usage log (handoff C3).
 */
public class SiteRankedListings implements RankedListings {

	private static final Logger log = LoggerFactory.getLogger(SiteRankedListings.class);

	private final LibraryCatalog libraryCatalog;

	private final PlatformAdapter kyobo;

	private final PlatformAdapter yes24;

	private final EgressLanes lanes;

	private final ParseSlots parseSlots;

	private final RankedListingsProperties properties;

	private final String blockedHostSuffix;

	private final Clock clock;

	public SiteRankedListings(LibraryCatalog libraryCatalog, List<PlatformAdapter> adapters, EgressLanes lanes,
			ParseSlots parseSlots, RankedListingsProperties properties, String blockedHostSuffix, Clock clock) {
		this.libraryCatalog = libraryCatalog;
		this.kyobo = adapter(adapters, PlatformType.KYOBO_V1);
		this.yes24 = adapter(adapters, PlatformType.YES24_V1);
		this.lanes = lanes;
		this.parseSlots = parseSlots;
		this.properties = properties;
		this.blockedHostSuffix = blockedHostSuffix.toLowerCase(Locale.ROOT);
		this.clock = clock;
	}

	@Override
	public Result fetch(long libraryId, Sort sort, int page) {
		if (page < 1) {
			throw new IllegalArgumentException("page starts at 1: " + page);
		}
		Optional<SearchTarget> found = libraryCatalog.findEnabledTarget(libraryId);
		if (found.isEmpty()) {
			return new Failed("unknown_library", "library " + libraryId + " is not an enabled library");
		}
		SearchTarget target = found.get();
		PlatformAdapter adapter;
		URI uri;
		int pageSize;
		if (target.platformType() == PlatformType.KYOBO_V1) {
			adapter = kyobo;
			pageSize = properties.kyoboPageSize();
			uri = kyoboUri(target.baseUrl(), page, pageSize);
		}
		else if (target.platformType() == PlatformType.YES24_V1) {
			adapter = yes24;
			pageSize = properties.yes24PageSize();
			uri = yes24Uri(target.baseUrl(), page, pageSize);
		}
		else {
			return new Failed("unsupported_screen", target.platformType() + " has no popular order");
		}
		String host = SiteHostNames.hostOrNull(uri);
		if (host == null || blocked(host)) {
			// never sent: the KYOBO subscription servers are not asked (cycle 13 R1-6)
			return new Failed("blocked_host", "host " + host + " is not requested");
		}
		SiteResponse response;
		try {
			response = lanes.fetch(uri, clock.instant().plus(properties.requestDeadline()));
		}
		catch (UpstreamException ex) {
			String reason = ex.kind() == UpstreamException.Kind.TIMEOUT ? "upstream_timeout" : "upstream_error";
			log.info("ranked_list_failed library={} reason={} detail={}", libraryId, reason, ex.getMessage());
			return new Failed(reason, ex.getMessage());
		}
		if (!response.isSuccess()) {
			log.info("ranked_list_failed library={} reason=upstream_error status={}", libraryId, response.status());
			return new Failed("upstream_error", "status " + response.status() + " via " + response.via());
		}
		ParsedPage parsed;
		try {
			parsed = parseSlots.parse(() -> adapter.parse(response.body(), response.declaredCharset()));
		}
		catch (UpstreamException ex) {
			log.info("ranked_list_failed library={} reason=parse_error detail={}", libraryId, ex.getMessage());
			return new Failed("parse_error", ex.getMessage());
		}
		List<Row> rows = new ArrayList<>(parsed.items().size());
		for (int i = 0; i < parsed.items().size(); i++) {
			ParsedItem item = parsed.items().get(i);
			rows.add(new Row((page - 1) * pageSize + i + 1, item.title(), item.author(), item.publisher(),
					itemKey(item.detailPath()), item.loaned(), item.reservations(), item.holdings()));
		}
		return new Page(parsed.totalCount(), pageSize, rows);
	}

	boolean blocked(String host) {
		String lower = host.toLowerCase(Locale.ROOT);
		return lower.endsWith(blockedHostSuffix) || ("." + lower).equals(blockedHostSuffix);
	}

	/** Regular KYOBO content list in loan order (pretest 2026-10-24, {@code scripts/crawl/probe_popular.py}). */
	static URI kyoboUri(String baseUrl, int page, int pageSize) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/elibrary-front/content/contentList.ink")
			.queryParam("cttsDvsnCode", "001")
			.queryParam("contentAll", "Y")
			.queryParam("ctgrId", "")
			.queryParam("orderByKey", "loanNmvl")
			.queryParam("pageIndex", page)
			.queryParam("recordCount", pageSize)
			.encode()
			.build()
			.toUri();
	}

	/** YES24 V1 search with no text in lending order: every book of the site (pretest 2026-10-24). */
	static URI yes24Uri(String baseUrl, int page, int pageSize) {
		return UriComponentsBuilder.fromUriString(baseUrl)
			.path("/search/")
			.queryParam("src_key", "")
			.queryParam("srch_order", "title")
			.queryParam("sort", "lending")
			.queryParam("view", pageSize)
			.queryParam("page_num", page)
			.build(true)
			.toUri();
	}

	/** {@code brcd} of a KYOBO book page, {@code goods_id} of a YES24 one; the catalog's {@code holding.item_key}. */
	static String itemKey(String detailPath) {
		if (detailPath == null) {
			return null;
		}
		var params = UriComponentsBuilder.fromUriString(detailPath).build().getQueryParams();
		String key = params.getFirst("brcd");
		if (key == null) {
			key = params.getFirst("goods_id");
		}
		return key == null || key.isBlank() ? null : key;
	}

	private static PlatformAdapter adapter(List<PlatformAdapter> adapters, PlatformType type) {
		return adapters.stream()
			.filter(adapter -> adapter.platform() == type)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("no adapter for " + type));
	}

}
