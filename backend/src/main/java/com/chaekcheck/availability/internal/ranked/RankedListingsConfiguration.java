package com.chaekcheck.availability.internal.ranked;

import java.time.Clock;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chaekcheck.availability.RankedListings;
import com.chaekcheck.availability.internal.adapter.PlatformAdapter;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.egress.EgressLanes;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.library.LibraryCatalog;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RankedListingsProperties.class)
class RankedListingsConfiguration {

	@Bean
	RankedListings rankedListings(LibraryCatalog libraryCatalog, List<PlatformAdapter> adapters, EgressLanes egressLanes,
			ParseSlots parseSlots, RankedListingsProperties properties, AvailabilityProperties availability) {
		return new SiteRankedListings(libraryCatalog, adapters, egressLanes, parseSlots, properties,
				availability.kyobo().subscriptionHostSuffix(), Clock.systemUTC());
	}

}
