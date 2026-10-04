package com.chaekcheck.auth.internal.login;

import java.util.List;
import java.util.Map;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Reads the provider's user number from the profile API and keeps nothing else: no nickname, no picture
 * (ADR-0039, C23). Naver puts it at {@code response.id}, Kakao at the top-level {@code id} (a number, kept as
 * text).
 */
final class ProviderUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	static final String SUBJECT = "sub";

	private final DefaultOAuth2UserService delegate;

	ProviderUserService(DefaultOAuth2UserService delegate) {
		this.delegate = delegate;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
		String registrationId = userRequest.getClientRegistration().getRegistrationId();
		LoginProvider provider = LoginProvider.fromRegistrationId(registrationId)
			.orElseThrow(() -> invalid("Unknown registration"));
		Map<String, Object> attributes = delegate.loadUser(userRequest).getAttributes();
		Object id = switch (provider) {
			case NAVER -> attributes.get("response") instanceof Map<?, ?> response ? response.get("id") : null;
			case KAKAO -> attributes.get("id");
		};
		if (id == null || id.toString().isBlank()) {
			throw invalid("No user id in the profile response");
		}
		return new DefaultOAuth2User(List.of(), Map.of(SUBJECT, id.toString()), SUBJECT);
	}

	private static OAuth2AuthenticationException invalid(String description) {
		return new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info_response", description, null));
	}

}
