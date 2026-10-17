package com.chaekcheck.availability.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.fetch.HostThrottle;
import com.chaekcheck.availability.internal.fetch.InMemoryHostQueueBoard;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.state.InMemoryLastTimeRecord;
import com.chaekcheck.availability.internal.status.EgressLaneStatus;
import com.chaekcheck.availability.internal.status.LookupStatusService;

/** {@code GET /api/v1/lookup-status} (AC-7): the answer shape, no cache, and no library call however often it is asked. */
@WebMvcTest(LookupStatusController.class)
@Import(LookupStatusControllerTests.Status.class)
class LookupStatusControllerTests {

	/** A real service over empty memory; the fetch path objects are mocks that must stay untouched. */
	@TestConfiguration
	static class Status {

		@Bean
		SiteFetcher siteFetcher() {
			return mock(SiteFetcher.class);
		}

		@Bean
		HostThrottle hostThrottle() {
			return mock(HostThrottle.class);
		}

		@Bean
		LookupStatusService lookupStatusService() {
			InMemoryLastTimeRecord refusals = new InMemoryLastTimeRecord();
			return new LookupStatusService(new SearchSlots(40, refusals, new LookupOpsCounters(), Clock.systemUTC()),
					refusals, new InMemoryLastTimeRecord(), new InMemoryHostQueueBoard(), EgressLaneStatus.NONE,
					AdapterFixtures.LOOKUP_STATUS, Clock.systemUTC());
		}

	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	SiteFetcher siteFetcher;

	@Autowired
	HostThrottle hostThrottle;

	@Test
	void answersTheSignalWithoutCachingOrCallingALibrary() {
		MvcTestResult result = null;
		for (int i = 0; i < 60; i++) {
			result = mvc.get().uri("/api/v1/lookup-status").exchange();
		}

		assertThat(result).hasStatusOk()
			.hasHeader("Cache-Control", "no-cache")
			.bodyJson()
			.isLenientlyEqualTo("{\"slots\":40,\"inUse\":0,\"level\":\"GREEN\",\"reason\":\"NONE\","
					+ "\"queuedLibraryIds\":[]}");
		assertThat(result).bodyJson().extractingPath("$.serverTime").asString().endsWith("Z");
		verifyNoInteractions(siteFetcher, hostThrottle);
	}

}
