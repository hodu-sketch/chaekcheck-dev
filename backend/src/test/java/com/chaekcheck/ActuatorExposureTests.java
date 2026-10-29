package com.chaekcheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Actuator exposes only health (readiness + liveness probes) and nothing else (cycle 27, ADR-0169).
 * Graceful shutdown is configured for zero-downtime deployment.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorExposureTests {

	@Autowired
	MockMvcTester mvc;

	@Value("${server.shutdown}")
	String shutdownMode;

	@Value("${spring.lifecycle.timeout-per-shutdown-phase}")
	String shutdownTimeout;

	@Test
	void readinessProbeIsUp() {
		assertThat(mvc.get().uri("/actuator/health/readiness"))
			.hasStatusOk()
			.bodyJson().extractingPath("status").isEqualTo("UP");
	}

	@Test
	void livenessProbeIsUp() {
		assertThat(mvc.get().uri("/actuator/health/liveness"))
			.hasStatusOk()
			.bodyJson().extractingPath("status").isEqualTo("UP");
	}

	@Test
	void envEndpointIsNotExposed() {
		assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void beansEndpointIsNotExposed() {
		assertThat(mvc.get().uri("/actuator/beans")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void infoEndpointIsNotExposed() {
		assertThat(mvc.get().uri("/actuator/info")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void metricsEndpointIsNotExposed() {
		assertThat(mvc.get().uri("/actuator/metrics")).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void gracefulShutdownIsConfigured() {
		assertThat(shutdownMode).isEqualTo("graceful");
	}

	@Test
	void shutdownTimeoutIs40Seconds() {
		assertThat(shutdownTimeout).isEqualTo("40s");
	}

}
