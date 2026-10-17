package com.chaekcheck.availability.internal.config;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chaekcheck.availability.internal.adapter.PlatformAdapter;
import com.chaekcheck.availability.internal.CacheStatistics;
import com.chaekcheck.availability.internal.egress.BlockedLaneRetest;
import com.chaekcheck.availability.internal.egress.BlockedLanes;
import com.chaekcheck.availability.internal.egress.DefaultEgressLaneStatus;
import com.chaekcheck.availability.internal.egress.EgressLane;
import com.chaekcheck.availability.internal.egress.EgressLanes;
import com.chaekcheck.availability.internal.egress.HostRules;
import com.chaekcheck.availability.internal.egress.InMemoryBlockedLanes;
import com.chaekcheck.availability.internal.egress.InMemoryLaneThrottle;
import com.chaekcheck.availability.internal.egress.LaneCounters;
import com.chaekcheck.availability.internal.egress.LaneThrottle;
import com.chaekcheck.availability.internal.fetch.CaffeineSearchPageCache;
import com.chaekcheck.availability.internal.fetch.FetchServiceSiteFetcher;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.fetch.InMemoryHostQueueBoard;
import com.chaekcheck.availability.internal.fetch.LaneSiteFetcher;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.fetch.LibrarySiteTls;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.fetch.SearchPageCache;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.ops.LookupOpsSummaryLog;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolvers;
import com.chaekcheck.availability.internal.resolve.CaffeineDetailVerdictCache;
import com.chaekcheck.availability.internal.resolve.DetailLookupResolver;
import com.chaekcheck.availability.internal.resolve.DetailPageFetcher;
import com.chaekcheck.availability.internal.resolve.DetailVerdictCache;
import com.chaekcheck.availability.LookupHealth;
import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.state.DbErrorRecord;
import com.chaekcheck.availability.internal.state.InMemoryLastTimeRecord;
import com.chaekcheck.availability.internal.state.RefusalRecord;
import com.chaekcheck.availability.internal.status.DefaultLookupHealth;
import com.chaekcheck.availability.internal.status.EgressLaneStatus;
import com.chaekcheck.availability.internal.status.LookupStatusService;
import com.chaekcheck.availability.internal.title.BookKeyNormalizer;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.usagelog.UsageLog;
import com.github.benmanes.caffeine.cache.Ticker;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ AvailabilityProperties.class, LookupStateProperties.class })
class AvailabilityConfiguration {

	/**
	 * Runs page loads. Loads block on the throttle and on HTTP, so they use virtual threads. The executor itself has
	 * no limit; the work is capped before it gets here (SearchSlots, batch parallelism).
	 */
	@Bean(destroyMethod = "close")
	ExecutorService availabilityExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	// Egress lanes (cycle 22, handoff 1-15, ADR-0130): the full lane list, the clients per lane, the per-lane host
	// throttle and the paused lanes behind store interfaces (memory), and EgressLanes picking the lane.

	/** Every configured lane: direct, then one per proxy. The mode filters them in {@link #egressLanes}. */
	private static List<EgressLane> laneList(AvailabilityProperties properties) {
		return EgressLane.list(properties.egress().proxies(), properties.egress().proxyVendor());
	}

	/**
	 * Direct calls (one HTTP client pair per lane) or calls through the fetch servers, which name the lane
	 * (ADR-0015). There is no automatic switch between them.
	 */
	@Bean
	SiteFetcher siteFetcher(AvailabilityProperties properties, JsonMapper jsonMapper) {
		AvailabilityProperties.Fetch fetch = properties.fetch();
		if (fetch.mode() == AvailabilityProperties.Fetch.Mode.FETCH_SERVICE) {
			return new FetchServiceSiteFetcher(
					fetch.servers()
						.stream()
						.map(server -> new FetchServiceSiteFetcher.Server(server.id(), server.url()))
						.toList(),
					fetch.tokenHeader(), fetch.token(), fetch.connectTimeout(), fetch.readTimeout(),
					fetch.restDuration(), jsonMapper, Clock.systemUTC());
		}
		// JDK HttpClient per lane: connect timeout and proxy on the client; read timeout (time to response headers),
		// total time and body size on each request (DirectSiteFetcher). Host names with an underscore go through
		// Apache HttpClient with the same lane proxy (cycle 18, ADR-0111).
		return new LaneSiteFetcher(laneList(properties), LibrarySiteTls.sslContext(), properties.connectTimeout(),
				properties.readTimeout(), properties.totalTimeout(), properties.maxBodySize().toBytes(),
				properties.userAgent(), properties.egress().credentials(), Clock.systemUTC());
	}

	/** Per lane and host: 1 at a time + 1s; Kyobo subscription hosts pooled at 3 per lane (ADR-0094). */
	@Bean
	HostRules hostRules(AvailabilityProperties properties) {
		return new HostRules(properties.hostMaxConcurrency(), properties.hostMinInterval(),
				properties.kyobo().subscriptionMaxConcurrency(), properties.kyobo().subscriptionHostSuffix());
	}

	@Bean
	LaneThrottle laneThrottle(LookupStateProperties state) {
		return switch (state.stateStore()) {
			case MEMORY -> new InMemoryLaneThrottle(Clock.systemUTC());
		};
	}

	/** Which lane is paused on which host, with the block / retest rule (spec R13-4). */
	@Bean
	BlockedLanes blockedLanes(AvailabilityProperties properties, LookupStateProperties state) {
		AvailabilityProperties.Egress.Block block = properties.egress().block();
		return switch (state.stateStore()) {
			case MEMORY -> new InMemoryBlockedLanes(block.failuresToBlock(), block.firstPause(), block.maxPause());
		};
	}

	@Bean
	LaneCounters laneCounters(AvailabilityProperties properties) {
		return new LaneCounters(laneList(properties));
	}

	/** The one place a library request picks its lane; fails the start when the mode leaves no lane. */
	@Bean
	EgressLanes egressLanes(AvailabilityProperties properties, HostRules hostRules, LaneThrottle laneThrottle,
			BlockedLanes blockedLanes, SiteFetcher siteFetcher, LaneCounters laneCounters) {
		return new EgressLanes(properties.egress().mode(), laneList(properties), hostRules, laneThrottle, blockedLanes,
				siteFetcher, laneCounters, Clock.systemUTC());
	}

	/** Retests paused lanes: 10 min, doubling, 7 days at most. */
	@Bean(destroyMethod = "close")
	BlockedLaneRetest blockedLaneRetest(EgressLanes egressLanes, AvailabilityProperties properties) {
		AvailabilityProperties.Egress.Block block = properties.egress().block();
		return new BlockedLaneRetest(egressLanes, block.retestCheckInterval(), block.retestTimeout());
	}

	@Bean
	SearchPageCache searchPageCache(AvailabilityProperties properties, ExecutorService availabilityExecutor) {
		return new CaffeineSearchPageCache(properties.cacheTtl(), properties.caches().searchPageMaxSize(),
				availabilityExecutor,
				Ticker.systemTicker());
	}

	/** The one set of parse slots, shared by search pages, detail pages and later the extension uploads (cycle 20). */
	@Bean
	ParseSlots parseSlots(AvailabilityProperties properties) {
		return new ParseSlots(properties.parseSlots().max(), properties.parseSlots().maxWait());
	}

	/** Which libraries wait for a busy host (cycle 20, spec R4); the store is picked like the records below. */
	@Bean
	HostQueueBoard hostQueueBoard(LookupStateProperties state) {
		return switch (state.stateStore()) {
			case MEMORY -> new InMemoryHostQueueBoard();
		};
	}

	@Bean
	LibraryPageFetcher libraryPageFetcher(List<PlatformAdapter> adapters, SearchPageCache cache, EgressLanes egressLanes,
			AvailabilityProperties properties, ParseSlots parseSlots, HostQueueBoard hostQueueBoard) {
		return new LibraryPageFetcher(adapters, cache, egressLanes, properties.requestDeadline(), Clock.systemUTC(),
				new BookKeyNormalizer(), parseSlots, hostQueueBoard);
	}

	@Bean
	DetailVerdictCache detailVerdictCache(AvailabilityProperties properties, ExecutorService availabilityExecutor) {
		return new CaffeineDetailVerdictCache(properties.cacheTtl(), properties.caches().detailVerdictMaxSize(),
				availabilityExecutor,
				Ticker.systemTicker());
	}

	/** Logs the caches' statistics every {@code caches.stats-log-interval} (ADR-0066). */
	@Bean(destroyMethod = "close")
	CacheStatsLog cacheStatsLog(List<CacheStatistics> caches, AvailabilityProperties properties) {
		return new CacheStatsLog(caches, properties.caches().statsLogInterval());
	}

	/** The one path detail pages are loaded on: portal blank verdicts and the holding stream. */
	@Bean
	DetailPageFetcher detailPageFetcher(List<PlatformAdapter> adapters, DetailVerdictCache detailVerdictCache,
			EgressLanes egressLanes, ParseSlots parseSlots, HostQueueBoard hostQueueBoard) {
		return new DetailPageFetcher(adapters, detailVerdictCache, egressLanes, Clock.systemUTC(), parseSlots,
				hostQueueBoard);
	}

	@Bean
	AvailabilityResolvers availabilityResolvers(DetailPageFetcher detailPageFetcher) {
		return new AvailabilityResolvers(new DetailLookupResolver(detailPageFetcher, Clock.systemUTC()));
	}

	// Memory state behind store interfaces (cycle 20, ADR-0133): chaekcheck.lookup.state-store picks the
	// implementation; only "memory" exists, so each switch has one arm until a shared store is added.

	/** When a search was last refused with 503 (spec R1: RED for 10s after). */
	@Bean
	RefusalRecord refusalRecord(LookupStateProperties state) {
		return switch (state.stateStore()) {
			case MEMORY -> new InMemoryLastTimeRecord();
		};
	}

	/** When the API last failed on the database (overall review #9: GRAY for 30s after). */
	@Bean
	DbErrorRecord dbErrorRecord(LookupStateProperties state) {
		return switch (state.stateStore()) {
			case MEMORY -> new InMemoryLastTimeRecord();
		};
	}

	/** The module's one public method besides REST: the root ApiExceptionHandler reports DB errors through it. */
	@Bean
	LookupHealth lookupHealth(DbErrorRecord dbErrorRecord, LookupOpsCounters counters) {
		return new DefaultLookupHealth(dbErrorRecord, counters, Clock.systemUTC());
	}

	/**
	 * The egress lanes as the status signal reads them (spec R1 additions, cycle 22): the mode and the paused
	 * (lane, host) count; the vendor pause stays false until cycle 17.
	 */
	@Bean
	EgressLaneStatus egressLaneStatus(AvailabilityProperties properties, BlockedLanes blockedLanes) {
		return new DefaultEgressLaneStatus(properties.egress().mode(), blockedLanes, Clock.systemUTC());
	}

	/** Judges GET /api/v1/lookup-status from memory, with a 1s memo (ADR-0118). */
	@Bean
	LookupStatusService lookupStatusService(SearchSlots searchSlots, RefusalRecord refusalRecord,
			DbErrorRecord dbErrorRecord, HostQueueBoard hostQueueBoard, EgressLaneStatus egressLaneStatus,
			AvailabilityProperties properties) {
		return new LookupStatusService(searchSlots, refusalRecord, dbErrorRecord, hostQueueBoard, egressLaneStatus,
				properties.lookupStatus(), Clock.systemUTC());
	}

	/** One INFO line a minute with the lookup counters (cycle 20, ADR-0132, AC-40). */
	@Bean(destroyMethod = "close")
	LookupOpsSummaryLog lookupOpsSummaryLog(LookupOpsCounters counters, SearchSlots searchSlots, ParseSlots parseSlots,
			HostQueueBoard hostQueueBoard, EgressLaneStatus egressLaneStatus, LaneCounters laneCounters,
			BlockedLanes blockedLanes, AvailabilityProperties properties) {
		return new LookupOpsSummaryLog(counters, searchSlots, parseSlots, hostQueueBoard, egressLaneStatus,
				laneCounters, blockedLanes, properties.lookupStatus(), properties.opsSummary().interval(),
				Clock.systemUTC());
	}

	/** Caps the searches running at once, so the unbounded executor above never gets unbounded work. */
	@Bean
	SearchSlots searchSlots(AvailabilityProperties properties, RefusalRecord refusalRecord,
			LookupOpsCounters counters) {
		return new SearchSlots(properties.maxConcurrentSearches(), refusalRecord, counters, Clock.systemUTC());
	}

	/** Counters of the one-minute operations summary (cycle 20, ADR-0132). */
	@Bean
	LookupOpsCounters lookupOpsCounters() {
		return new LookupOpsCounters();
	}

	@Bean
	BatchRunner batchRunner(LibraryCatalog libraryCatalog, LibraryPageFetcher fetcher,
			DetailPageFetcher detailPageFetcher, AvailabilityResolvers resolvers, AvailabilityProperties properties,
			ExecutorService availabilityExecutor, SearchSlots searchSlots, UsageLog usageLog,
			LookupOpsCounters counters) {
		return new BatchRunner(libraryCatalog, fetcher, detailPageFetcher, resolvers, properties.batch(),
				properties.detail().deadline(), properties.hostMinInterval(), availabilityExecutor, searchSlots,
				Clock.systemUTC(), usageLog, counters);
	}

}
