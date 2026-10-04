package com.chaekcheck.auth.internal.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Replaces Spring's default, which keeps every provider token in memory for the life of the server (C15). Nothing
 * is kept. The one exception: Naver's access token is handed to the success handler for this request only, so a
 * pending sign-up can revoke the link if it is abandoned (step 6). Kakao is unlinked with the admin key instead.
 */
final class NonStoringAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

	static final String NAVER_ACCESS_TOKEN_ATTRIBUTE = NonStoringAuthorizedClientRepository.class.getName()
			+ ".naverAccessToken";

	@Override
	public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
			Authentication principal, HttpServletRequest request) {
		return null;
	}

	@Override
	public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
			HttpServletRequest request, HttpServletResponse response) {
		String registrationId = authorizedClient.getClientRegistration().getRegistrationId();
		if (LoginProvider.fromRegistrationId(registrationId).orElse(null) == LoginProvider.NAVER) {
			request.setAttribute(NAVER_ACCESS_TOKEN_ATTRIBUTE, authorizedClient.getAccessToken().getTokenValue());
		}
	}

	@Override
	public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
			HttpServletRequest request, HttpServletResponse response) {
	}

}
