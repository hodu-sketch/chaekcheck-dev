package com.chaekcheck.popular.internal;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.chaekcheck.popular.internal.fetch.HttpKpipaTransport;
import com.chaekcheck.popular.internal.fetch.KpipaTransport;
import com.chaekcheck.popular.internal.fetch.RequestPacer;
import com.chaekcheck.popular.internal.schedule.FetchDecision;
import com.chaekcheck.popular.internal.schedule.FetchJob;
import com.chaekcheck.popular.internal.schedule.FetchScheduler;
import com.chaekcheck.popular.internal.store.PopularStore;

/**
 * Beans of the popular module. Scheduling is switched on here (plan cycle 10 design #7): the one in the auth module
 * is off while login is off. The fetch itself runs on {@link FetchScheduler}'s own thread.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PopularProperties.class)
@EnableScheduling
class PopularConfiguration {

	/** The real site. Tests put a fake in its place (plan cycle 10 C3). */
	@Bean
	KpipaTransport kpipaTransport(PopularProperties properties) {
		PopularProperties.Fetch fetch = properties.fetch();
		return new HttpKpipaTransport(fetch.baseUrl(), fetch.connectTimeout(), fetch.readTimeout(),
				fetch.maxBodySize().toBytes(), fetch.coverMaxBodySize().toBytes(), fetch.userAgent());
	}

	@Bean
	RequestPacer.Ticker kpipaTicker() {
		return RequestPacer.Ticker.SYSTEM;
	}

	@Bean
	Clock popularClock() {
		return Clock.systemUTC();
	}

	@Bean
	FetchDecision fetchDecision(PopularStore store, Clock popularClock, PopularProperties properties) {
		return new FetchDecision(store, popularClock, properties.fetch().zone());
	}

	@Bean
	FetchJob fetchJob(FetchDecision fetchDecision, PopularStore store, KpipaTransport kpipaTransport,
			PopularProperties properties, RequestPacer.Ticker kpipaTicker, Clock popularClock,
			ObjectProvider<RankingChanged> listeners) {
		return new FetchJob(fetchDecision, store, kpipaTransport, properties.fetch(), kpipaTicker, popularClock,
				listeners.orderedStream().toList());
	}

	/** Only with {@code chaekcheck.popular.fetch.enabled=true}: no start-up fetch and no daily fetch otherwise. */
	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "chaekcheck.popular.fetch.enabled", havingValue = "true")
	FetchScheduler fetchScheduler(FetchJob fetchJob) {
		return new FetchScheduler(fetchJob);
	}

}
