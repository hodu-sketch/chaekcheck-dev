package com.chaekcheck.auth.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * When {@code chaekcheck.auth.enabled} is not {@code true}, adds the Spring Security auto-configurations to
 * {@code spring.autoconfigure.exclude}, so the application has no security filter, no generated user and no
 * security headers, exactly as in cycle 04 (ADR-0046, AC-21). Exclusions already configured are kept.
 * <p>
 * Runs after the config files (and {@code .env}) are loaded, so the switch can be set in {@code application.yml},
 * in {@code .env} or the environment ({@code CHAEKCHECK_AUTH_ENABLED}), or on the command line.
 */
public class AuthToggleEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String ENABLED = "chaekcheck.auth.enabled";

	private static final String EXCLUDE = "spring.autoconfigure.exclude";

	/** Servlet-side security auto-configurations of Spring Boot 4.1 (the reactive ones never apply here). */
	static final List<String> SECURITY_AUTO_CONFIGURATIONS = List.of(
			"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
			"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
			"org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
			"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
			"org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration",
			"org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration",
			"org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
			"org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Binder binder = Binder.get(environment);
		if (binder.bind(ENABLED, Boolean.class).orElse(false)) {
			return;
		}
		Set<String> excluded = new LinkedHashSet<>(
				binder.bind(EXCLUDE, Bindable.listOf(String.class)).orElse(List.of()));
		excluded.addAll(SECURITY_AUTO_CONFIGURATIONS);
		environment.getPropertySources()
			.addFirst(new MapPropertySource("chaekcheckAuthDisabled",
					Map.of(EXCLUDE, String.join(",", new ArrayList<>(excluded)))));
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
