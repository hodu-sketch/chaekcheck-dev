package com.chaekcheck.availability.internal.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

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
 */
@ConfigurationProperties("chaekcheck.availability")
public record AvailabilityProperties(Duration cacheTtl, Caches caches, int hostMaxConcurrency,
		Duration hostMinInterval, Duration connectTimeout, Duration readTimeout, Duration requestDeadline, Batch batch,
		Detail detail, Kyobo kyobo, Yes24 yes24, String userAgent, Fetch fetch, Duration totalTimeout,
		DataSize maxBodySize, int maxConcurrentSearches, ParseSlots parseSlots, LookupStatus lookupStatus,
		OpsSummary opsSummary) {

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

	/** @param recordCount results per page requested from KYOBO sites */
	public record Kyobo(int recordCount) {
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
