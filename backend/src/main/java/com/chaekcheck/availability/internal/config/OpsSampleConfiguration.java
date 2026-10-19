package com.chaekcheck.availability.internal.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.availability.internal.egress.LaneCounters;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.ops.JdbcOpsSampleStore;
import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.ops.OpsSampler;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.status.EgressLaneStatus;

/**
 * The 10-second lookup state rows in {@code availability.lookup_sample} (user decision 2026-10-19). Off with
 * {@code chaekcheck.availability.ops-sample.enabled=false} (the test profile).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpsSampleProperties.class)
@ConditionalOnBooleanProperty(name = "chaekcheck.availability.ops-sample.enabled", matchIfMissing = true)
class OpsSampleConfiguration {

	@Bean(initMethod = "start", destroyMethod = "close")
	OpsSampler opsSampler(LookupOpsCounters counters, SearchSlots searchSlots, ParseSlots parseSlots,
			HostQueueBoard hostQueueBoard, EgressLaneStatus egressLaneStatus, LaneCounters laneCounters,
			AvailabilityProperties properties, OpsSampleProperties sample, JdbcClient jdbc) {
		return new OpsSampler(counters, searchSlots, parseSlots, hostQueueBoard, egressLaneStatus, laneCounters,
				properties.lookupStatus(), new JdbcOpsSampleStore(jdbc), sample.interval(), sample.retention(),
				Clock.systemUTC());
	}

}
