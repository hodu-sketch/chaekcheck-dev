package com.chaekcheck.availability.internal.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import com.chaekcheck.availability.internal.egress.EgressMode;

/**
 * Settings for calling library sites (design-delta "외부 연동", "묶음 실행 규칙"). Values live in application.yml.
 * @param readTimeout until a library's response headers arrive (direct mode)
 * @param requestDeadline longest wait for one page outside a batch (the "more" API)
 * @param totalTimeout one library request in direct mode, body included; past it the request is cancelled
 * (upstream_timeout)
 * @param maxBodySize largest library body read in direct mode; a bigger one stops being read and the request fails
 * (upstream_error)
 * @param maxConcurrentSearches batch streams and "more" calls running at once; more are refused with 503
 * {@code server_busy} (SearchSlots)
 * @param caches size limit of each cache and how often their statistics are logged (cycle 08, ADR-0066)
 * @param parseSlots pages parsed at once and the wait for a slot (cycle 20, AC-33)
 * @param lookupStatus the status signal's times and list size (cycle 20, ADR-0118)
 * @param opsSummary the one-minute operations summary line (cycle 20, ADR-0132)
 * @param hostMaxConcurrency requests at a time from one egress lane to one library host (webshare cycle (in cycle 20): per lane) [확정 1]
 * @param hostMinInterval wait after the previous request from that lane to that host ended [확정 1s]
 * @param egress the egress lanes: mode, proxy list, vendor, credentials, blocked-lane rule (webshare cycle (in cycle 20), ADR-0130)
 */
@ConfigurationProperties("chaekcheck.availability")
public record AvailabilityProperties(Duration cacheTtl, Caches caches, int hostMaxConcurrency,
		Duration hostMinInterval, Duration connectTimeout, Duration readTimeout, Duration requestDeadline, Batch batch,
		Detail detail, Kyobo kyobo, Yes24 yes24, String userAgent, Fetch fetch, Duration totalTimeout,
		DataSize maxBodySize, int maxConcurrentSearches, ParseSlots parseSlots, LookupStatus lookupStatus,
		OpsSummary opsSummary, Egress egress) {

	/**
	 * The egress lanes (webshare cycle (in cycle 20), handoff 1-15, ADR-0130; docs/maintenance/settings-index.md).
	 * @param mode {@code all} (default) / {@code proxy-only} / {@code direct-only} [확정 values, 안 spelling]
	 * @param proxies comma separated {@code host:port} list, one proxy lane each in order ({@code proxy-01} …);
	 * empty = the direct lane only. Production values are secrets (spec R13-2)
	 * @param proxyVendor the vendor name written in logs for the proxy lanes [안 webshare]
	 * @param proxyUsername one username for every proxy lane; empty = the vendor authorizes this server's IP
	 * (production). Environment only, never logged (user decision 2026-10-17: local PC uses the pair)
	 * @param proxyPassword as above
	 * @param block the blocked-lane rule
	 */
	public record Egress(EgressMode mode, String proxies, String proxyVendor, String proxyUsername,
			String proxyPassword, Block block) {

		public Egress {
			mode = mode == null ? EgressMode.ALL : mode;
		}

		/** {@code {username, password}} when both are set, else null. */
		public String[] credentials() {
			return proxyUsername == null || proxyUsername.isBlank() || proxyPassword == null || proxyPassword.isEmpty()
					? null : new String[] { proxyUsername, proxyPassword };
		}

		/**
		 * When a lane stops sending to a host and how it is tried again ({@code from-cycle-20-blocked-ip-resume.md}).
		 * @param failuresToBlock block signals in a row that pause the lane on that host [안 5, dev per ADR-0094]
		 * @param firstPause the first pause [확정 10m]
		 * @param maxPause the longest pause after doubling [확정 7d]
		 * @param retestCheckInterval how often pauses that ran out are looked for [안 30s]
		 * @param retestTimeout deadline of one retest request, throttle wait included [안 20s]
		 */
		public record Block(int failuresToBlock, Duration firstPause, Duration maxPause, Duration retestCheckInterval,
				Duration retestTimeout) {
		}

	}

	/**
	 * Library pages parsed at the same time (cycle 20, overall review #4; docs/maintenance/settings-index.md).
	 * @param max slots [확정 8]
	 * @param maxWait longest wait for a slot once the body has arrived; then that library fails with parse_busy [확정 2s]
	 */
	public record ParseSlots(int max, Duration maxWait) {
	}

	/**
	 * The status signal {@code GET /api/v1/lookup-status} (cycle 20, ADR-0118; docs/maintenance/settings-index.md).
	 * @param memo how long one computed answer is handed out again [확정 1s]
	 * @param redAfterRefusal RED for this long after a 503 server_busy [확정 10s]
	 * @param grayAfterDbError GRAY (DB_ERROR) for this long after a DB error [확정 30s]
	 * @param queueWaitWindow a host-queue wait older than this is no longer counted [확정 10s = batch deadline + 2s]
	 * @param queueTimeoutWindow a throttle timeout keeps the host queued this long [확정 60s]
	 * @param maxQueuedLibraries longest {@code queuedLibraryIds} list [안 200]
	 */
	public record LookupStatus(Duration memo, Duration redAfterRefusal, Duration grayAfterDbError,
			Duration queueWaitWindow, Duration queueTimeoutWindow, int maxQueuedLibraries) {
	}

	/**
	 * The operations summary log line (cycle 20, overall review #5; docs/maintenance/settings-index.md).
	 * @param interval how often the line is written [확정 1m]
	 */
	public record OpsSummary(Duration interval) {
	}

	/**
	 * The in-memory caches (ADR-0006: 3 minutes; plan ADR-0066: one limit per cache, no weight limit).
	 * @param searchPageMaxSize search pages kept at most. TODO(확인 필요): 1,000 as before until step 9 measures it
	 * @param detailVerdictMaxSize detail page verdicts kept at most. TODO(확인 필요): as above
	 * @param statsLogInterval how often hits, misses, evictions and entries are logged [안 5m]
	 */
	public record Caches(long searchPageMaxSize, long detailVerdictMaxSize, Duration statsLogInterval) {
	}

	/**
	 * One batch of the search stream (design-delta "묶음 실행 규칙").
	 * @param rowLimit no new request once more than this many rows are counted [확정 20]
	 * @param parallelism libraries being fetched at once [안 5]
	 * @param deadline search pages must answer within this, from the batch start [확정 8s]
	 * @param launchCutoff no new request when less than this is left before the deadline [안 3s]
	 * @param maxCandidates candidates one "next batch" request may carry [안 50]
	 */
	public record Batch(int rowLimit, int parallelism, Duration deadline, Duration launchCutoff, int maxCandidates) {
	}

	/**
	 * Detail-page lookups for portal rows whose list verdict is blank (DetailLookupResolver).
	 * @param deadline from the batch start; rows not decided by then become UNKNOWN [확정 30s, 안 기준 시각]
	 */
	public record Detail(Duration deadline) {
	}

	/**
	 * @param recordCount results per page requested from KYOBO sites
	 * @param subscriptionMaxConcurrency requests at a time from one lane to all Kyobo subscription hosts together
	 * (webshare cycle (in cycle 20), ADR-0094) [확정 3, name 안]
	 * @param subscriptionHostSuffix the host names that form that pool [확정 .dkyobobook.co.kr]
	 */
	public record Kyobo(int recordCount, int subscriptionMaxConcurrency, String subscriptionHostSuffix) {
	}

	/** @param sort value of the YES24 {@code sort} parameter */
	public record Yes24(String sort) {
	}

	/**
	 * How library pages are fetched (ADR-0015). No automatic switch between modes.
	 * @param servers fetch servers in priority order (fetch-service mode only)
	 * @param restDuration how long a fetch server that refused a connection or answered 503 is skipped
	 */
	public record Fetch(Mode mode, List<Server> servers, String tokenHeader, String token, Duration connectTimeout,
			Duration readTimeout, Duration restDuration) {

		public Fetch {
			servers = servers == null ? List.of() : List.copyOf(servers);
		}

		public enum Mode {
			DIRECT, FETCH_SERVICE
		}

		public record Server(String id, URI url) {
		}

	}

}
