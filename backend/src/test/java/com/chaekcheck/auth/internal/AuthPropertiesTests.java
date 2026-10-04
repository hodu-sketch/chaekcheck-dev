package com.chaekcheck.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** Login on needs every key and stops at start with the missing property's name (ADR-0046). */
class AuthPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
				ValidationAutoConfiguration.class))
		.withUserConfiguration(AuthConfiguration.class);

	@Test
	void notCreatedWhenLoginIsOff() {
		runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AuthProperties.class));
	}

	@Test
	void failsAtStartWhenKeysAreMissing() {
		runner.withPropertyValues("chaekcheck.auth.enabled=true", "chaekcheck.auth.naver.client-id=test-id")
			.run(context -> {
				assertThat(context).hasFailed();
				String message = rootMessage(context.getStartupFailure());
				assertThat(message).contains("chaekcheck.auth.jwt.secret", "AUTH_JWT_SECRET",
						"chaekcheck.auth.naver.clientSecret", "chaekcheck.auth.kakao.clientId",
						"chaekcheck.auth.kakao.clientSecret");
				assertThat(message).doesNotContain("chaekcheck.auth.naver.clientId");
			});
	}

	@Test
	void bindsWhenEveryKeyIsSet() {
		runner.withPropertyValues("chaekcheck.auth.enabled=true", "chaekcheck.auth.jwt.secret=test-secret",
				"chaekcheck.auth.naver.client-id=n-id", "chaekcheck.auth.naver.client-secret=n-secret",
				"chaekcheck.auth.kakao.client-id=k-id", "chaekcheck.auth.kakao.client-secret=k-secret")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AuthProperties.class).kakao().clientId()).isEqualTo("k-id");
			});
	}

	private static String rootMessage(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable t = failure; t != null; t = t.getCause()) {
			messages.append(t.getMessage()).append('\n');
		}
		return messages.toString();
	}

}
