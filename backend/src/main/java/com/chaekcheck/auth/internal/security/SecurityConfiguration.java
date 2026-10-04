package com.chaekcheck.auth.internal.security;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.account.AccountStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * The application-wide security filter chain, owned by the auth module (ADR-0044). Only {@code /api/v1/me/**} needs
 * a login; every other path stays open as before (handoff C6). Other modules' paths appear here only as strings,
 * never through their code (C1).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuthEnabled
class SecurityConfiguration {

	/** The only paths that need a login (C6). The cookie reader and the authorization rule share this matcher. */
	static final RequestMatcher PROTECTED = new OrRequestMatcher(PathPatternRequestMatcher.pathPattern("/api/v1/me"),
			PathPatternRequestMatcher.pathPattern("/api/v1/me/**"));

	/** Provider callbacks that cannot send our CSRF header; they prove themselves by key or signature (C4). */
	static final String[] WEBHOOKS = { "/api/v1/auth/webhooks/kakao-unlink", "/api/v1/auth/webhooks/naver-unlink" };

	private static final int MIN_KEY_BYTES = 32;

	/**
	 * The token filter is added directly, not through {@code oauth2ResourceServer()}: that configurer exempts from CSRF
	 * every request in which its token resolver finds a token. That is right for an {@code Authorization} header, but
	 * our token is a cookie the browser sends by itself, so a forged change request would pass (review 2026-10-04).
	 */
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
			JwtDecoder jwtDecoder, AccountStore accounts, JsonMapper json,
			Customizer<OAuth2LoginConfigurer<HttpSecurity>> oauth2LoginCustomizer) {
		ProblemResponses problems = new ProblemResponses(json);
		JwtAuthenticationProvider jwtProvider = new JwtAuthenticationProvider(jwtDecoder);
		jwtProvider.setJwtAuthenticationConverter(new AccountJwtAuthenticationConverter(accounts));
		BearerTokenAuthenticationConverter tokenReader = new BearerTokenAuthenticationConverter();
		tokenReader.setBearerTokenResolver(new AccessTokenCookieResolver(PROTECTED));
		BearerTokenAuthenticationFilter tokenFilter = new BearerTokenAuthenticationFilter(new ProviderManager(jwtProvider),
				tokenReader);
		tokenFilter.setAuthenticationFailureHandler(problems);
		tokenFilter.setSecurityContextRepository(new RequestAttributeSecurityContextRepository());

		http.authorizeHttpRequests(requests -> requests.requestMatchers(PROTECTED).authenticated().anyRequest().permitAll())
			.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository).ignoringRequestMatchers(WEBHOOKS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			// The login lives in the token cookies; the session (10 minutes) only carries values during a login
			.securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(problems).accessDeniedHandler(problems))
			// Naver and Kakao login (auth.internal.login.LoginConfiguration)
			.oauth2Login(oauth2LoginCustomizer)
			.addFilterBefore(tokenFilter, AnonymousAuthenticationFilter.class);
		return http.build();
	}

	/**
	 * {@code XSRF-TOKEN} cookie: readable by the web scripts (not HttpOnly), Secure, SameSite=Lax, on the site root so
	 * every screen path can read it (C13).
	 */
	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieName(AuthCookies.CSRF_TOKEN);
		repository.setHeaderName(AuthCookies.CSRF_HEADER);
		repository.setCookiePath("/");
		repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
		return repository;
	}

	/** HS256 only (C8): a token signed with any other algorithm, or not signed, is rejected. */
	@Bean
	JwtDecoder jwtDecoder(SecretKey accessTokenKey) {
		return NimbusJwtDecoder.withSecretKey(accessTokenKey).macAlgorithm(MacAlgorithm.HS256).build();
	}

	/** Base64 of at least 32 random bytes from {@code AUTH_JWT_SECRET} (C8). The value is never logged. */
	@Bean
	SecretKey accessTokenKey(AuthProperties properties) {
		byte[] key;
		try {
			key = Base64.getDecoder().decode(properties.jwt().secret().strip());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("chaekcheck.auth.jwt.secret (AUTH_JWT_SECRET) is not valid base64");
		}
		if (key.length < MIN_KEY_BYTES) {
			throw new IllegalStateException("chaekcheck.auth.jwt.secret (AUTH_JWT_SECRET) must be base64 of at least "
					+ MIN_KEY_BYTES + " random bytes, got " + key.length);
		}
		return new SecretKeySpec(key, "HmacSHA256");
	}

}
