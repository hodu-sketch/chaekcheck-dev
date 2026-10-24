package com.chaekcheck.foryou.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.chaekcheck.availability.RankedListings;
import com.chaekcheck.catalog.BookCards;
import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.foryou.internal.build.BuildScheduler;
import com.chaekcheck.foryou.internal.build.ForYouBuild;
import com.chaekcheck.foryou.internal.listed.ListedSource;
import com.chaekcheck.foryou.internal.source.PopularSource;
import com.chaekcheck.foryou.internal.source.SiteListSource;
import com.chaekcheck.foryou.internal.store.ForYouStore;

/** Beans of the foryou module (cycle 23). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForYouProperties.class)
@EnableScheduling
class ForYouConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ForYouConfiguration.class);

	@Bean
	ForYouSnapshot forYouSnapshot(ForYouStore store) {
		return new ForYouSnapshot(store);
	}

	@Bean
	ForYouBuild forYouBuild(RankedListings rankedListings, BookCards bookCards, BookLookup bookLookup, ForYouStore store,
			ForYouProperties properties, ForYouSnapshot snapshot) {
		ForYouProperties.Sources sources = properties.sources();
		List<PopularSource> popular = new ArrayList<>();
		if (sources.kyoboElib().enabled()) {
			popular.add(new SiteListSource("kyobo_elib", rankedListings, sources.kyoboElib().libraryIds(),
					sources.kyoboElib().top()));
		}
		if (sources.yes24Elib().enabled()) {
			popular.add(new SiteListSource("yes24_elib", rankedListings, sources.yes24Elib().libraryIds(),
					sources.yes24Elib().top()));
		}
		// TODO(확인 필요): the sales rank, the 200 list and KLWAVE wait for their shape (step 0 #7, user PC check)
		if (sources.kpipaRank().enabled() || sources.kpipa200().enabled() || sources.klwave().enabled()) {
			log.warn("foryou: kpipa_rank / kpipa_200 / klwave are switched on but have no reader yet; they are skipped");
		}
		return new ForYouBuild(popular, bookCards, bookLookup, ListedSource.NONE, store, properties, snapshot::refresh,
				Clock.systemUTC());
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "chaekcheck.foryou.build.enabled", havingValue = "true")
	BuildScheduler forYouBuildScheduler(ForYouBuild build) {
		return new BuildScheduler(build);
	}

}
