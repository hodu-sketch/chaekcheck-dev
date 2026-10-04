package com.chaekcheck.auth.internal.login;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Starts a login only for {@code naver} and {@code kakao}. Any other name under the start path is left alone, so it
 * ends as the usual 404 {@code not_found} instead of Spring's 500 with an error log for an unknown registration
 * (security review 2026-10-04). The withdrawal re-authentication registrations (step 8) are not startable here.
 */
final class LoginAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	private static final Set<String> STARTABLE = Set.of(LoginProvider.NAVER.registrationId(),
			LoginProvider.KAKAO.registrationId());

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
		return delegate.resolve(request);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		return STARTABLE.contains(clientRegistrationId) ? delegate.resolve(request, clientRegistrationId) : null;
	}

}
