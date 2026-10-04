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
		runner.withPropertyValues("chaekcheck.auth.enabled=false")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AuthProperties.class));
	}

	@Test
	void failsAtStartWhenKeysAreMissing() {
		runner.withPropertyValues("chaekcheck.auth.enabled=true", "chaekcheck.auth.naver.client-id=test-id")
			.run(context -> {
				assertThat(context).hasFailed();
				String message = rootMessage(context.getStartupFailure());
				assertThat(message).contains("chaekcheck.auth.jwt.secret", "AUTH_JWT_SECRET",
						"chaekcheck.auth.naver.clientSecret", "chaekcheck.auth.kakao.clientId",
						"chaekcheck.auth.kakao.clientSecret", "chaekcheck.auth.publicBaseUrl", "AUTH_PUBLIC_BASE_URL");
				assertThat(message).doesNotContain("chaekcheck.auth.naver.clientId");
			});
	}

	@Test
	void bindsWhenEveryKeyIsSet() {
		runner.withPropertyValues("chaekcheck.auth.enabled=true", "chaekcheck.auth.jwt.secret=test-secret",
				"chaekcheck.auth.naver.client-id=n-id", "chaekcheck.auth.naver.client-secret=n-secret",
				"chaekcheck.auth.kakao.client-id=k-id", "chaekcheck.auth.kakao.client-secret=k-secret",
				"chaekcheck.auth.public-base-url=http://localhost:5173")
			.withPropertyValues(endpoints("naver"))
			.withPropertyValues(endpoints("kakao"))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AuthProperties.class).kakao().clientId()).isEqualTo("k-id");
			});
	}

	@Test
	void publicBaseUrlMustBeSchemeAndHostOnly() {
		runner.withPropertyValues("chaekcheck.auth.enabled=true", "chaekcheck.auth.public-base-url=localhost:5173/app")
			.run(context -> assertThat(rootMessage(context.getStartupFailure())).contains("chaekcheck.auth.publicBaseUrl",
					"scheme, host and optional port only"));
	}

	/** The endpoints normally come from application.yml, which this runner does not load. */
	private static String[] endpoints(String provider) {
		String prefix = "chaekcheck.auth." + provider + ".";
		return new String[] { prefix + "authorization-uri=https://example.test/authorize",
				prefix + "token-uri=https://example.test/token", prefix + "user-info-uri=https://example.test/me" };
	}

	private static String rootMessage(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable t = failure; t != null; t = t.getCause()) {
			messages.append(t.getMessage()).append('\n');
		}
		return messages.toString();
	}

}
