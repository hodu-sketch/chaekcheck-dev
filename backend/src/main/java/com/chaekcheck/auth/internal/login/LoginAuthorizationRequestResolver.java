package com.chaekcheck.auth.internal.login;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * Starts a login only for the four registrations. Any other name under the start path is left alone, so it ends as
 * the usual 404 {@code not_found} instead of Spring's 500 with an error log for an unknown registration (security
 * review 2026-10-04). The withdrawal re-authentication registrations ask the provider to sign in again
 * (ADR-0043): Naver {@code auth_type=reauthenticate}, Kakao {@code prompt=login}.
 */
final class LoginAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	private static final Set<String> STARTABLE = Set.of(LoginProvider.NAVER.registrationId(),
			LoginProvider.KAKAO.registrationId(), LoginProvider.NAVER.reauthRegistrationId(),
			LoginProvider.KAKAO.reauthRegistrationId());

	private final DefaultOAuth2AuthorizationRequestResolver delegate;

	LoginAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
		this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations,
				LoginConfiguration.AUTHORIZATION_BASE);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		String prefix = LoginConfiguration.AUTHORIZATION_BASE + "/";
		if (path.startsWith(prefix) && !STARTABLE.contains(path.substring(prefix.length()))) {
			return null;
		}
		return withReauthParameters(delegate.resolve(request));
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		return STARTABLE.contains(clientRegistrationId)
				? withReauthParameters(delegate.resolve(request, clientRegistrationId)) : null;
	}

	private static OAuth2AuthorizationRequest withReauthParameters(OAuth2AuthorizationRequest request) {
		if (request == null) {
			return null;
		}
		String registrationId = request.getAttribute(OAuth2ParameterNames.REGISTRATION_ID);
		if (!LoginProvider.isReauth(registrationId)) {
			return request;
		}
		Map<String, Object> extra = switch (LoginProvider.fromRegistrationId(registrationId).orElseThrow()) {
			case NAVER -> Map.of("auth_type", "reauthenticate");
			case KAKAO -> Map.of("prompt", "login");
		};
		return OAuth2AuthorizationRequest.from(request).additionalParameters(parameters -> parameters.putAll(extra)).build();
	}

}
