package com.chaekcheck.availability.internal.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chaekcheck.availability.internal.adapter.PlatformAdapter;
import com.chaekcheck.availability.internal.CacheStatistics;
import com.chaekcheck.availability.internal.fetch.CaffeineSearchPageCache;
import com.chaekcheck.availability.internal.fetch.DirectSiteFetcher;
import com.chaekcheck.availability.internal.fetch.FetchServiceSiteFetcher;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.fetch.HostRoutingSiteFetcher;
import com.chaekcheck.availability.internal.fetch.InMemoryHostQueueBoard;
import com.chaekcheck.availability.internal.fetch.HostThrottle;
import com.chaekcheck.availability.internal.fetch.InMemoryHostThrottle;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.fetch.LibrarySiteTls;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.fetch.SearchPageCache;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.fetch.UnderscoreHostSiteFetcher;
import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
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

	/** Direct calls or calls through the fetch servers (ADR-0015). There is no automatic switch between them. */
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
		// JDK HttpClient: connect timeout on the client; read timeout (time to response headers), total time and
		// body size on each request (DirectSiteFetcher).
		SSLContext sslContext = LibrarySiteTls.sslContext();
		HttpClient httpClient = HttpClient.newBuilder()
			.sslContext(sslContext)
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		DirectSiteFetcher direct = new DirectSiteFetcher(httpClient, properties.readTimeout(),
				properties.totalTimeout(), properties.maxBodySize().toBytes(), properties.userAgent(),
				Clock.systemUTC());
		// Host names with an underscore, which java.net.http refuses (cycle 18, ADR-0111): same limits and TLS
		return new HostRoutingSiteFetcher(direct, underscoreHostSiteFetcher(properties, sslContext));
	}

	private UnderscoreHostSiteFetcher underscoreHostSiteFetcher(AvailabilityProperties properties,
			SSLContext sslContext) {
		return UnderscoreHostSiteFetcher.create(sslContext, properties.connectTimeout(), properties.readTimeout(),
				properties.totalTimeout(), properties.maxBodySize().toBytes(), properties.userAgent(),
				Clock.systemUTC());
	}

	@Bean
	HostThrottle hostThrottle(AvailabilityProperties properties) {
		return new InMemoryHostThrottle(properties.hostMaxConcurrency(), properties.hostMinInterval(),
				Clock.systemUTC());
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
	LibraryPageFetcher libraryPageFetcher(List<PlatformAdapter> adapters, SearchPageCache cache, HostThrottle throttle,
			SiteFetcher siteFetcher, AvailabilityProperties properties, ParseSlots parseSlots,
			HostQueueBoard hostQueueBoard) {
		return new LibraryPageFetcher(adapters, cache, throttle, siteFetcher, properties.requestDeadline(),
				Clock.systemUTC(), new BookKeyNormalizer(), parseSlots, hostQueueBoard);
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
			HostThrottle throttle, SiteFetcher siteFetcher, ParseSlots parseSlots, HostQueueBoard hostQueueBoard) {
		return new DetailPageFetcher(adapters, detailVerdictCache, throttle, siteFetcher, Clock.systemUTC(),
				parseSlots, hostQueueBoard);
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
	 * The egress lanes as the status signal reads them (spec R1 additions). Cycle 17/22's lane code replaces this
	 * bean with a real one; until then nothing is paused and every lane is allowed (reason NONE).
	 */
	@Bean
	EgressLaneStatus egressLaneStatus() {
		return EgressLaneStatus.NONE;
	}

	/** Judges GET /api/v1/lookup-status from memory, with a 1s memo (ADR-0118). */
	@Bean
	LookupStatusService lookupStatusService(SearchSlots searchSlots, RefusalRecord refusalRecord,
			DbErrorRecord dbErrorRecord, HostQueueBoard hostQueueBoard, EgressLaneStatus egressLaneStatus,
			AvailabilityProperties properties) {
		return new LookupStatusService(searchSlots, refusalRecord, dbErrorRecord, hostQueueBoard, egressLaneStatus,
				properties.lookupStatus(), Clock.systemUTC());
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
