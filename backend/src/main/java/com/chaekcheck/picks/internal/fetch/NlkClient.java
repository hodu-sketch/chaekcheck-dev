package com.chaekcheck.picks.internal.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.picks.internal.PicksProperties;

/**
 * The requests of one fetch (plan cycle 14 C4): the list with one request, then the new covers, every request paced
 * by {@link RequestPacer} and logged (method, the path with the key replaced by {@code ***}, status, time, gap since
 * the request before). The key goes in the query ({@code key=}), so it is never logged, never put in an exception
 * message or a {@code failure_detail} (spec R1-7).
 */
public final class NlkClient {

	static final String LIST_PATH = "/NL/search/openApi/saseoApi.do";

	private static final Logger log = LoggerFactory.getLogger(NlkClient.class);

	private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

	private final NlkTransport transport;

	private final RequestPacer pacer;

	private final PicksProperties.Fetch settings;

	private int requestCount;

	public NlkClient(NlkTransport transport, RequestPacer pacer, PicksProperties.Fetch settings) {
		this.transport = transport;
		this.pacer = pacer;
		this.settings = settings;
	}

	/**
	 * One request for the picks registered from {@code today} minus the window to {@code today}.
	 * @return the answer body (status 200)
	 * @throws FetchFailure {@code key_missing} (no request sent), {@code network_error}, {@code request_rejected}
	 * (not 200, 3xx included) or {@code format_changed} (body over the limit)
	 */
	public byte[] fetchList(LocalDate today) throws InterruptedException {
		if (!settings.hasApiKey()) {
			throw new FetchFailure(FailureReason.KEY_MISSING, "no API key");
		}
		String query = "startRowNumApi=1&endRowNumApi=" + settings.endRow() + "&start_date="
				+ today.minus(settings.window()).format(DAY) + "&end_date=" + today.format(DAY);
		URI uri = URI.create(settings.baseUrl() + LIST_PATH + "?key="
				+ URLEncoder.encode(settings.apiKey().strip(), StandardCharsets.UTF_8) + "&" + query);
		String shown = LIST_PATH + "?key=***&" + query;
		NlkResponse response;
		try {
			response = send(uri, shown, settings.maxBodySize().toBytes());
		}
		catch (NlkTransport.BodyTooLargeException ex) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, "body over " + settings.maxBodySize());
		}
		catch (IOException ex) {
			throw new FetchFailure(FailureReason.NETWORK_ERROR,
					ex instanceof HttpTimeoutException ? "time-out" : "connection failed");
		}
		if (response.status() != 200) {
			throw new FetchFailure(FailureReason.REQUEST_REJECTED, "HTTP " + response.status());
		}
		return response.body();
	}

	/**
	 * One cover (ADR-0091): only an address {@link CoverUrls} allows, redirects not followed, 200 with an
	 * {@code image/} type, at most the cover body limit.
	 * @return the original image bytes, or empty when the cover failed
	 */
	public Optional<byte[]> fetchCover(String coverSource) throws InterruptedException {
		Optional<String> allowed = CoverUrls.normalize(coverSource);
		if (allowed.isEmpty() || !allowed.get().equals(coverSource)) {
			log.warn("NLK cover not requested: address not allowed");
			return Optional.empty();
		}
		URI uri = URI.create(coverSource);
		NlkResponse response;
		try {
			response = send(uri, uri.getRawPath(), settings.coverMaxBodySize().toBytes());
		}
		catch (IOException ex) {
			log.warn("NLK cover {} failed: {}", uri.getRawPath(), ex.getClass().getSimpleName());
			return Optional.empty();
		}
		String type = response.contentType() == null ? "" : response.contentType().toLowerCase(Locale.ROOT).strip();
		if (response.status() != 200 || !type.startsWith("image/")) {
			log.warn("NLK cover {} not used: HTTP {}, {}", uri.getRawPath(), response.status(), type);
			return Optional.empty();
		}
		return Optional.of(response.body());
	}

	/** Requests sent so far (the list and the covers). */
	public int requestCount() {
		return requestCount;
	}

	private NlkResponse send(URI uri, String shown, long maxBytes) throws IOException, InterruptedException {
		Duration gap = pacer.awaitTurn();
		long start = System.nanoTime();
		requestCount++;
		try {
			NlkResponse response = transport.get(uri, maxBytes);
			log.info("NLK GET {} -> {} in {}ms, {} bytes{}", shown, response.status(),
					(System.nanoTime() - start) / 1_000_000, response.body() == null ? 0 : response.body().length,
					gap == null ? "" : ", " + gap.toMillis() + "ms after the request before");
			return response;
		}
		catch (IOException ex) {
			log.info("NLK GET {} failed in {}ms: {}{}", shown, (System.nanoTime() - start) / 1_000_000,
					ex.getClass().getSimpleName(), gap == null ? "" : ", " + gap.toMillis() + "ms after the request before");
			throw ex;
		}
		finally {
			pacer.finished();
		}
	}

}
