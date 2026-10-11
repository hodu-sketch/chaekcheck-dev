package com.chaekcheck.popular.internal.fetch;

import java.io.IOException;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.popular.internal.PopularCategory;

/**
 * Talks to the publishing distribution network's public statistics screens (plan research kpipa.md 2절, 2-1절) for
 * one fetch. One session GET of the rank page gives the cookie and the {@code m_sCsrf} token, which both screens
 * accept ("토큰 하나로 두 화면"), so a month is 1 + 17 + 16 = 34 requests (AC-12: 35 or fewer). No request is sent
 * twice in a fetch [안]; the first failure stops it (the next day tries again, R1-3). Every request waits for the
 * {@link RequestPacer} and is logged at INFO with method, path, list, status, time taken and the gap since the last
 * request (AC-12).
 */
public final class KpipaClient {

	static final String RANK_PAGE = "/home/v3/addition/adiStatsCtgSaleRank";

	static final String RANK_SEARCH = RANK_PAGE + "/search";

	static final String SALES_PAGE = "/home/v3/addition/adiStatsCtgSaleCnt";

	static final String SALES_SEARCH = SALES_PAGE + "/search";

	private static final Logger log = LoggerFactory.getLogger(KpipaClient.class);

	private static final Pattern TOKEN = Pattern.compile("var\\s+m_sCsrf\\s*=\\s*\"([^\"]+)\"\\s*;");

	private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

	private final KpipaTransport.Connection connection;

	private final RequestPacer pacer;

	private final String baseUrl;

	private int requestCount;

	/** @param baseUrl the site, without a trailing slash; used for the Referer headers */
	public KpipaClient(KpipaTransport.Connection connection, RequestPacer pacer, String baseUrl) {
		this.connection = connection;
		this.pacer = pacer;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}

	/** Requests sent so far, failed ones included. */
	public int requestCount() {
		return requestCount;
	}

	/**
	 * The 17 lists of {@code month} and the 16 sales counts, with the display order.
	 * @throws FetchFailure on the first request or answer that is not as expected
	 */
	public MonthRanking fetchRanking(YearMonth month) throws InterruptedException {
		String token = openSession();
		String yyyymm = month.format(YYYYMM);
		Map<String, String> rankHeaders = postHeaders(token, baseUrl + RANK_PAGE);
		Map<PopularCategory, List<RankedBook>> lists = new EnumMap<>(PopularCategory.class);
		for (PopularCategory category : PopularCategory.values()) {
			String form = "rdoSearchType=bookType&txtDate=" + yyyymm + "&searchCtg=" + category.siteId()
					+ "&strTypeArr=&searchOrder=&depth=1depth&gubun=N";
			KpipaResponse response = post(RANK_SEARCH, rankHeaders, form, category);
			lists.put(category, RankPageParser.parse(response.text(), category.shortName()));
		}
		// TODO(확인 필요): this Referer worked once (2026-10-12 fixture); the first real fetch confirms it
		Map<String, String> salesHeaders = postHeaders(token, baseUrl + SALES_PAGE);
		Map<PopularCategory, Long> sales = new EnumMap<>(PopularCategory.class);
		for (PopularCategory category : PopularCategory.withSales()) {
			String form = "rdoSearchType=bookType&rdoSearchPeriod=month&txtFromDate=" + yyyymm + "&txtToDate="
					+ yyyymm + "&searchCtg=" + category.siteId() + "&depth=1depth";
			KpipaResponse response = post(SALES_SEARCH, salesHeaders, form, category);
			sales.put(category, SalesCountParser.parse(response.text(), category.shortName()));
		}
		Map<PopularCategory, Integer> order = CategoryOrder.of(sales);
		List<CategoryRanking> categories = new ArrayList<>();
		for (PopularCategory category : PopularCategory.values()) {
			categories.add(new CategoryRanking(category, sales.get(category), order.get(category), lists.get(category)));
		}
		return new MonthRanking(month, categories, requestCount);
	}

	/**
	 * One small cover. Failures are not errors of the fetch: the book is shown with its title (ADR-0075).
	 * @return the image, or empty when the answer is not 200 with an {@code image/} type or the request failed
	 */
	public Optional<CoverImage> fetchCover(String path) throws InterruptedException {
		try {
			KpipaResponse response = send(KpipaRequest.cover(path), "cover");
			if (response.status() == 200 && response.contentType() != null
					&& response.contentType().toLowerCase(Locale.ROOT).startsWith("image/")) {
				return Optional.of(new CoverImage(response.contentType(), response.body()));
			}
			log.warn("kpipa cover {} not stored: status {} type {}", path, response.status(), response.contentType());
		}
		catch (IOException ex) {
			log.warn("kpipa cover {} failed: {}", path, ex.toString());
		}
		return Optional.empty();
	}

	private String openSession() throws InterruptedException {
		KpipaResponse response;
		try {
			response = send(KpipaRequest.get(RANK_PAGE, Map.of()), "session");
		}
		catch (IOException ex) {
			throw networkError("session", ex);
		}
		if (response.status() != 200) {
			throw new FetchFailure(FailureReason.SESSION_FAILED, "session: status " + response.status());
		}
		Matcher matcher = TOKEN.matcher(response.text());
		if (!matcher.find()) {
			throw new FetchFailure(FailureReason.SESSION_FAILED, "session: no m_sCsrf");
		}
		return matcher.group(1);
	}

	private KpipaResponse post(String path, Map<String, String> headers, String form, PopularCategory category)
			throws InterruptedException {
		KpipaResponse response;
		try {
			response = send(KpipaRequest.post(path, headers, form), category.shortName());
		}
		catch (KpipaTransport.BodyTooLargeException ex) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, category.shortName() + ": " + ex.getMessage(), ex);
		}
		catch (IOException ex) {
			throw networkError(category.shortName(), ex);
		}
		if (response.status() != 200) {
			throw new FetchFailure(FailureReason.REQUEST_REJECTED,
					category.shortName() + " " + path + ": status " + response.status());
		}
		return response;
	}

	private KpipaResponse send(KpipaRequest request, String list) throws IOException, InterruptedException {
		Duration gap = pacer.awaitTurn();
		long start = pacer.nanoTime();
		String status = "-";
		requestCount++;
		try {
			KpipaResponse response = connection.send(request);
			status = Integer.toString(response.status());
			return response;
		}
		catch (IOException ex) {
			status = ex.getClass().getSimpleName();
			throw ex;
		}
		finally {
			pacer.finished();
			log.info("kpipa {} {} list={} status={} took={}ms gap={}", request.method(), request.path(), list, status,
					Duration.ofNanos(pacer.nanoTime() - start).toMillis(),
					gap == null ? "first" : gap.toMillis() + "ms");
		}
	}

	private static Map<String, String> postHeaders(String token, String referer) {
		return Map.of("Content-Type", "application/x-www-form-urlencoded", "X-CSRF-TOKEN", token, "X-Requested-With",
				"XMLHttpRequest", "Referer", referer);
	}

	private static FetchFailure networkError(String list, IOException ex) {
		return new FetchFailure(FailureReason.NETWORK_ERROR, list + ": " + ex, ex);
	}

	/** A stored cover: the answer's Content-Type and bytes. */
	public record CoverImage(String contentType, byte[] image) {
	}

}
