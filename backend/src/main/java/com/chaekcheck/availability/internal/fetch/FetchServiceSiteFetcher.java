package com.chaekcheck.availability.internal.fetch;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.UpstreamException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fetches library pages through the fetch servers (ADR-0015, design-delta flow 4). Servers are a fixed list in
 * priority order; a request goes to the first one that is not resting.
 * <ul>
 * <li>Connection refused, connect timeout or 503: that server rests for {@code restDuration} and the request goes
 * to the next server, while time remains before the deadline.</li>
 * <li>Read timeout on the fetch server: no failover (the library may already have been called), TIMEOUT.</li>
 * <li>200 with a library-side failure ({@code outcome} TIMEOUT, BODY_TOO_LARGE or ERROR): no failover, the library
 * fails. BODY_TOO_LARGE (cycle 20) is marked {@link UpstreamException#bodyTooLarge()} so it is counted as
 * {@code body_too_large}; an outcome this version does not know is ERROR, so a newer fetch server does not break
 * an older main service.</li>
 * <li>Every server resting: ERROR. Never falls back to calling the library directly.</li>
 * </ul>
 * The resting state is per instance (plan-feedback C2-U-1).
 */
public class FetchServiceSiteFetcher implements SiteFetcher {

	private static final Logger log = LoggerFactory.getLogger(FetchServiceSiteFetcher.class);

	private final List<Server> servers;

	private final String tokenHeader;

	private final String token;

	private final Duration readTimeout;

	private final Duration restDuration;

	private final HttpClient httpClient;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	private final Map<String, Instant> restingUntil = new ConcurrentHashMap<>();

	public FetchServiceSiteFetcher(List<Server> servers, String tokenHeader, String token, Duration connectTimeout,
			Duration readTimeout, Duration restDuration, JsonMapper jsonMapper, Clock clock) {
		if (servers.isEmpty()) {
			throw new IllegalArgumentException("fetch-service mode needs at least one server");
		}
		this.servers = List.copyOf(servers);
		this.tokenHeader = tokenHeader;
		this.token = token;
		this.readTimeout = readTimeout;
		this.restDuration = restDuration;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Override
	public SiteResponse fetch(URI uri, Instant deadline) {
		String requestId = UUID.randomUUID().toString();
		boolean failedOver = false;
		for (Server server : servers) {
			Instant now = clock.instant();
			if (!now.isBefore(deadline)) {
				break;
			}
			Instant rest = restingUntil.get(server.id());
			if (rest != null && now.isBefore(rest)) {
				continue;
			}
			try {
				SiteResponse response = call(server, uri, requestId, deadline);
				log.info("fetch-service via={} host={} path={} requestId={} failedOver={} status={}", server.id(),
						HostThrottle.hostPort(uri), uri.getRawPath(), requestId, failedOver, response.status());
				return response;
			}
			catch (ServerUnavailable ex) {
				restingUntil.put(server.id(), clock.instant().plus(restDuration));
				log.warn("fetch server {} unavailable ({}); resting for {} and trying the next one", server.id(),
						ex.getMessage(), restDuration);
				failedOver = true;
			}
		}
		throw UpstreamException.error("no fetch server available for " + HostThrottle.hostPort(uri)
				+ " (all resting or deadline passed)");
	}

	private SiteResponse call(Server server, URI uri, String requestId, Instant deadline) {
		Duration left = Duration.between(clock.instant(), deadline);
		Duration timeout = left.compareTo(readTimeout) < 0 ? left : readTimeout;
		HttpRequest request = HttpRequest.newBuilder(server.url().resolve("/v1/fetches"))
			.timeout(timeout)
			.header("Content-Type", "application/json")
			.header(tokenHeader, token)
			.POST(HttpRequest.BodyPublishers.ofByteArray(
					jsonMapper.writeValueAsBytes(new FetchRequest(uri.toString(), requestId))))
			.build();
		HttpResponse<byte[]> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
		}
		catch (HttpConnectTimeoutException | ConnectException ex) {
			throw new ServerUnavailable(ex.toString());
		}
		catch (HttpTimeoutException ex) {
			// the fetch server may already have called the library: do not send the same request again
			throw new UpstreamException(UpstreamException.Kind.TIMEOUT,
					"fetch server " + server.id() + " did not answer in time", ex);
		}
		catch (IOException ex) {
			if (ex.getCause() instanceof ConnectException) {
				throw new ServerUnavailable(ex.toString());
			}
			throw new UpstreamException(UpstreamException.Kind.ERROR, "fetch server " + server.id() + " failed", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new UpstreamException(UpstreamException.Kind.ERROR, "interrupted", ex);
		}
		if (response.statusCode() == 503) {
			throw new ServerUnavailable("503");
		}
		if (response.statusCode() != 200) {
			throw UpstreamException.error("fetch server " + server.id() + " answered " + response.statusCode());
		}
		FetchResult result = jsonMapper.readValue(response.body(), FetchResult.class);
		return switch (result.outcome()) {
			case "RESPONDED" -> new SiteResponse(result.statusOrZero(), result.contentType(), result.location(),
					result.body(), server.id());
			case "TIMEOUT" -> throw UpstreamException.timeout(
					"library " + HostThrottle.hostPort(uri) + " timed out on fetch server " + server.id());
			case "BODY_TOO_LARGE" -> throw UpstreamException.bodyTooLarge(uri, -1,
					result.receivedBytes() == null ? -1 : result.receivedBytes(), null);
			default -> throw UpstreamException.error("library " + HostThrottle.hostPort(uri) + " failed on fetch server "
					+ server.id() + ": " + result.outcome());
		};
	}

	/** One fetch server from the fixed list. */
	public record Server(String id, URI url) {
	}

	record FetchRequest(String url, String requestId) {
	}

	/**
	 * Fetch server answer. {@code outcome}: RESPONDED (the library answered with {@code status}), TIMEOUT,
	 * BODY_TOO_LARGE (cycle 20, with {@code receivedBytes}), or CONNECT_FAILED / ERROR. {@code body} is Base64 in the
	 * JSON; Jackson decodes it straight into bytes, so the body is never held as a String as well (cycle 08 handoff
	 * step 5-5).
	 */
	record FetchResult(String outcome, Integer status, String contentType, String location, byte[] body,
			Instant startedAt, Instant endedAt, Long receivedBytes) {

		int statusOrZero() {
			return status == null ? 0 : status;
		}

	}

	private static final class ServerUnavailable extends RuntimeException {

		ServerUnavailable(String message) {
			super(message, null, false, false);
		}

	}

}
