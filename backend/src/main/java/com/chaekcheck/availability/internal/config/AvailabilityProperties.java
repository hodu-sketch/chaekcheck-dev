package com.chaekcheck.availability.internal.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for calling library sites (design-delta "외부 연동", "묶음 실행 규칙"). Values live in application.yml.
 * @param requestDeadline longest wait for one page outside a batch (the "more" API)
 */
@ConfigurationProperties("chaekcheck.availability")
public record AvailabilityProperties(Duration cacheTtl, long cacheMaxSize, int hostMaxConcurrency,
		Duration hostMinInterval, Duration connectTimeout, Duration readTimeout, Duration requestDeadline, Batch batch,
		Detail detail, Kyobo kyobo, Yes24 yes24, String userAgent, Fetch fetch) {

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
