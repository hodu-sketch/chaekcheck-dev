package com.chaekcheck.picks.internal;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.chaekcheck.picks.internal.fetch.HttpNlkTransport;
import com.chaekcheck.picks.internal.fetch.NlkTransport;
import com.chaekcheck.picks.internal.fetch.RequestPacer;
import com.chaekcheck.picks.internal.schedule.FetchDecision;
import com.chaekcheck.picks.internal.schedule.FetchJob;
import com.chaekcheck.picks.internal.schedule.FetchScheduler;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * Beans of the picks module. Scheduling is switched on here (as in cycle 10): the one in the auth module is off while
 * login is off. The fetch itself runs on {@link FetchScheduler}'s own thread.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PicksProperties.class)
@EnableScheduling
class PicksConfiguration {

	/** The real API. Tests put a fake in its place (plan cycle 14 C3). */
	@Bean(destroyMethod = "close")
	HttpNlkTransport nlkTransport(PicksProperties properties) {
		PicksProperties.Fetch fetch = properties.fetch();
		return new HttpNlkTransport(fetch.connectTimeout(), fetch.readTimeout(), fetch.userAgent());
	}

	@Bean
	RequestPacer.Ticker picksTicker() {
		return RequestPacer.Ticker.SYSTEM;
	}

	@Bean
	Clock picksClock() {
		return Clock.systemUTC();
	}

	@Bean
	FetchDecision fetchDecision(PicksStore store, Clock picksClock, PicksProperties properties) {
		return new FetchDecision(store, picksClock, properties.fetch().zone());
	}

	@Bean
	FetchJob fetchJob(FetchDecision fetchDecision, PicksStore store, NlkTransport nlkTransport,
			PicksProperties properties, RequestPacer.Ticker picksTicker, Clock picksClock,
			ObjectProvider<PicksChanged> listeners) {
		return new FetchJob(fetchDecision, store, nlkTransport, properties.fetch(), picksTicker, picksClock,
				listeners.orderedStream().toList());
	}

	/** Only with {@code chaekcheck.picks.fetch.enabled=true}: no start-up fetch and no daily fetch otherwise. */
	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "chaekcheck.picks.fetch.enabled", havingValue = "true")
	FetchScheduler fetchScheduler(FetchJob fetchJob) {
		return new FetchScheduler(fetchJob);
	}

}
