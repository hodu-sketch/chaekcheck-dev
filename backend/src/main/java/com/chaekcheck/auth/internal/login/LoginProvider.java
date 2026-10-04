package com.chaekcheck.auth.internal.login;

import java.util.Optional;

/**
 * The two providers. A registration id is the provider name, or the name plus {@code -reauth} for the
 * withdrawal re-authentication registrations (step 8). {@code method} is the value in {@code auth.login_method}.
 */
public enum LoginProvider {

	NAVER("naver"), KAKAO("kakao");

	private final String registrationId;

	LoginProvider(String registrationId) {
		this.registrationId = registrationId;
	}

	public String registrationId() {
		return registrationId;
	}

	public String method() {
		return name();
	}

	/** {@code naver}, {@code kakao}, {@code naver-reauth}, {@code kakao-reauth}; anything else is empty. */
	public static Optional<LoginProvider> fromRegistrationId(String registrationId) {
		if (registrationId == null) {
			return Optional.empty();
		}
		String name = registrationId.endsWith("-reauth")
				? registrationId.substring(0, registrationId.length() - "-reauth".length()) : registrationId;
		for (LoginProvider provider : values()) {
			if (provider.registrationId.equals(name)) {
				return Optional.of(provider);
			}
		}
		return Optional.empty();
	}

}
