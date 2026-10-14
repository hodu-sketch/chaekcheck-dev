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
import com.chaekcheck.availability.internal.fetch.HostRoutingSiteFetcher;
import com.chaekcheck.availability.internal.fetch.HostThrottle;
import com.chaekcheck.availability.internal.fetch.InMemoryHostThrottle;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.fetch.LibrarySiteTls;
import com.chaekcheck.availability.internal.fetch.SearchPageCache;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.fetch.UnderscoreHostSiteFetcher;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolvers;
import com.chaekcheck.availability.internal.resolve.CaffeineDetailVerdictCache;
import com.chaekcheck.availability.internal.resolve.DetailLookupResolver;
import com.chaekcheck.availability.internal.resolve.DetailPageFetcher;
import com.chaekcheck.availability.internal.resolve.DetailVerdictCache;
import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.title.BookKeyNormalizer;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.usagelog.UsageLog;
import com.github.benmanes.caffeine.cache.Ticker;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AvailabilityProperties.class)
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

	@Bean
	LibraryPageFetcher libraryPageFetcher(List<PlatformAdapter> adapters, SearchPageCache cache, HostThrottle throttle,
			SiteFetcher siteFetcher, AvailabilityProperties properties) {
		return new LibraryPageFetcher(adapters, cache, throttle, siteFetcher, properties.requestDeadline(),
				Clock.systemUTC(), new BookKeyNormalizer());
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
			HostThrottle throttle, SiteFetcher siteFetcher) {
		return new DetailPageFetcher(adapters, detailVerdictCache, throttle, siteFetcher, Clock.systemUTC());
	}

	@Bean
	AvailabilityResolvers availabilityResolvers(DetailPageFetcher detailPageFetcher) {
		return new AvailabilityResolvers(new DetailLookupResolver(detailPageFetcher, Clock.systemUTC()));
	}

	/** Caps the searches running at once, so the unbounded executor above never gets unbounded work. */
	@Bean
	SearchSlots searchSlots(AvailabilityProperties properties) {
		return new SearchSlots(properties.maxConcurrentSearches());
	}

	@Bean
	BatchRunner batchRunner(LibraryCatalog libraryCatalog, LibraryPageFetcher fetcher,
			DetailPageFetcher detailPageFetcher, AvailabilityResolvers resolvers, AvailabilityProperties properties,
			ExecutorService availabilityExecutor, SearchSlots searchSlots, UsageLog usageLog) {
		return new BatchRunner(libraryCatalog, fetcher, detailPageFetcher, resolvers, properties.batch(),
				properties.detail().deadline(), properties.hostMinInterval(), availabilityExecutor, searchSlots,
				Clock.systemUTC(), usageLog);
	}

}
