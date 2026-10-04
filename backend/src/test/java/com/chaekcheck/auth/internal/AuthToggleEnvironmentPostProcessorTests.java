package com.chaekcheck.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class AuthToggleEnvironmentPostProcessorTests {

	private final AuthToggleEnvironmentPostProcessor processor = new AuthToggleEnvironmentPostProcessor();

	@Test
	void excludesSecurityWhenSwitchIsMissing() {
		MockEnvironment environment = new MockEnvironment();

		processor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(excluded(environment))
			.containsExactlyElementsOf(AuthToggleEnvironmentPostProcessor.SECURITY_AUTO_CONFIGURATIONS);
	}

	@Test
	void keepsExclusionsAlreadyConfigured() {
		MockEnvironment environment = new MockEnvironment().withProperty("chaekcheck.auth.enabled", "false")
			.withProperty("spring.autoconfigure.exclude", "com.example.SomeAutoConfiguration");

		processor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(excluded(environment)).startsWith("com.example.SomeAutoConfiguration")
			.containsAll(AuthToggleEnvironmentPostProcessor.SECURITY_AUTO_CONFIGURATIONS);
	}

	@Test
	void leavesSecurityAloneWhenSwitchIsOn() {
		MockEnvironment environment = new MockEnvironment().withProperty("chaekcheck.auth.enabled", "true");

		processor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("spring.autoconfigure.exclude")).isNull();
	}

	private static String[] excluded(MockEnvironment environment) {
		return Arrays.stream(environment.getProperty("spring.autoconfigure.exclude").split(","))
			.map(String::trim)
			.toArray(String[]::new);
	}

}
