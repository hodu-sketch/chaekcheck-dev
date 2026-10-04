package com.chaekcheck.auth.internal.login;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.account.AccountStore;
import com.chaekcheck.auth.internal.token.TokenService;

import tools.jackson.databind.json.JsonMapper;

/**
 * Naver and Kakao login with Spring Security's {@code oauth2Login} (handoff step 5).
 * <ul>
 * <li>start: {@code /api/v1/auth/oauth2/authorization/{naver|kakao}}, callback:
 * {@code /api/v1/auth/login/oauth2/code/{registrationId}}</li>
 * <li>redirect URIs come from {@code chaekcheck.auth.public-base-url}, never from the request (C18)</li>
 * <li>client id and secret are sent as form parameters (both providers' guides)</li>
 * <li>no scopes: only the user number is read (Kakao consent items stay off)</li>
 * <li>PKCE off for both: Kakao's guide has no PKCE parameters; for Naver's plain OAuth flow it is unconfirmed
 * (TODO(확인 필요): try a real Naver call with PKCE and turn it on if accepted, research.md 1절)</li>
 * </ul>
 * The {@code naver-reauth} and {@code kakao-reauth} registrations are for withdrawal (step 8, ADR-0043).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuthEnabled
class LoginConfiguration {

	static final String AUTHORIZATION_BASE = "/api/v1/auth/oauth2/authorization";

	/**
	 * The registrations of the providers in {@code chaekcheck.auth.providers}: login, and withdrawal
	 * re-authentication (step 8: same app, own redirect URI, extra parameters added by the resolver).
	 */
	@Bean
	ClientRegistrationRepository clientRegistrationRepository(AuthProperties properties) {
		String base = properties.publicBaseUrl();
		List<ClientRegistration> registrations = new ArrayList<>();
		for (LoginProvider provider : LoginProvider.values()) {
			if (properties.enabled(provider)) {
				AuthProperties.Provider settings = provider == LoginProvider.NAVER ? properties.naver() : properties.kakao();
				String userName = provider == LoginProvider.NAVER ? "response" : "id";
				registrations.add(registration(provider.registrationId(), settings, userName, base));
				registrations.add(registration(provider.reauthRegistrationId(), settings, userName, base));
			}
		}
		return new InMemoryClientRegistrationRepository(registrations);
	}

	/** Registration ids that can be started and called back (AC-22). */
	static Set<String> offered(AuthProperties properties) {
		Set<String> offered = new HashSet<>();
		for (LoginProvider provider : LoginProvider.values()) {
			if (properties.enabled(provider)) {
				offered.add(provider.registrationId());
				offered.add(provider.reauthRegistrationId());
			}
		}
		return offered;
	}

	/**
	 * 404 for login addresses of providers that are off or do not exist, before Spring Security's chain (whose
	 * OAuth2 filters would answer them otherwise). Registered in the servlet container directly.
	 */
	@Bean
	FilterRegistrationBean<ProviderPathFilter> providerPathFilter(AuthProperties properties, JsonMapper json) {
		FilterRegistrationBean<ProviderPathFilter> registration = new FilterRegistrationBean<>(
				new ProviderPathFilter(offered(properties), json));
		registration.addUrlPatterns(AUTHORIZATION_BASE + "/*", LoginFailureHandler.CALLBACK_BASE + "*");
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
		return registration;
	}

	@Bean
	Customizer<OAuth2LoginConfigurer<HttpSecurity>> oauth2LoginCustomizer(ClientRegistrationRepository registrations,
			AuthProperties properties, AccountStore accounts, TokenService tokens) {
		JdkClientHttpRequestFactory providerCalls = providerCalls(properties.providerCalls());
		return login -> login.clientRegistrationRepository(registrations)
			// A custom page turns off Spring's generated login page; nothing is ever sent there
			.loginPage("/")
			.authorizationEndpoint(endpoint -> endpoint.baseUri(AUTHORIZATION_BASE)
				.authorizationRequestRepository(new LoginAuthorizationRequestRepository())
				.authorizationRequestResolver(new LoginAuthorizationRequestResolver(registrations, offered(properties))))
			.redirectionEndpoint(endpoint -> endpoint.baseUri(LoginFailureHandler.CALLBACK_BASE + "*"))
			.tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient(providerCalls)))
			.userInfoEndpoint(endpoint -> endpoint.userService(new ProviderUserService(userInfoClient(providerCalls))))
			.authorizedClientRepository(new NonStoringAuthorizedClientRepository())
			.successHandler(new LoginSuccessHandler(accounts, tokens, Clock.systemUTC()))
			.failureHandler(new LoginFailureHandler());
	}

	private static ClientRegistration registration(String registrationId, AuthProperties.Provider settings,
			String userNameAttribute, String publicBaseUrl) {
		String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
				: publicBaseUrl;
		return ClientRegistration.withRegistrationId(registrationId)
			.clientId(settings.clientId())
			.clientSecret(settings.clientSecret())
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri(base + LoginFailureHandler.CALLBACK_BASE + registrationId)
			.authorizationUri(settings.authorizationUri())
			.tokenUri(settings.tokenUri())
			.userInfoUri(settings.userInfoUri())
			.userNameAttributeName(userNameAttribute)
			.clientName(registrationId)
			.clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(false).build())
			.build();
	}

	/** Connect 3 s, read 5 s (C20). Only these provider calls use it; library fetching keeps its own settings. */
	static JdkClientHttpRequestFactory providerCalls(AuthProperties.ProviderCalls settings) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build());
		factory.setReadTimeout(settings.readTimeout());
		return factory;
	}

	/**
	 * Spring's token client with the timeouts above. Naver's guide lists {@code state} as a token request parameter,
	 * so it is sent to Naver too.
	 */
	private static RestClientAuthorizationCodeTokenResponseClient tokenClient(JdkClientHttpRequestFactory factory) {
		RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
		client.setRestClient(RestClient.builder()
			.requestFactory(factory)
			.configureMessageConverters(converters -> converters.registerDefaults()
				.addCustomConverter(new FormHttpMessageConverter())
				.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
			.defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
			.build());
		client.addParametersConverter(grant -> {
			LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
			if (LoginProvider.fromRegistrationId(grant.getClientRegistration().getRegistrationId())
				.orElse(null) == LoginProvider.NAVER) {
				parameters.add(OAuth2ParameterNames.STATE,
						grant.getAuthorizationExchange().getAuthorizationRequest().getState());
			}
			return parameters;
		});
		return client;
	}

	private static DefaultOAuth2UserService userInfoClient(JdkClientHttpRequestFactory factory) {
		RestTemplate rest = new RestTemplate(factory);
		rest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
		DefaultOAuth2UserService service = new DefaultOAuth2UserService();
		service.setRestOperations(rest);
		return service;
	}

}
