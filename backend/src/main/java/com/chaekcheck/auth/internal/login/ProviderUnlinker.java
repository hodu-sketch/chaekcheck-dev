package com.chaekcheck.auth.internal.login;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;

/**
 * Ends the link between a provider account and chaekcheck at the provider: Kakao {@code unlink} with the admin key
 * ({@code target_id_type=user_id}), Naver {@code revoke} of the access token. One request, no retry; a failure is
 * only logged, never with a token or key (C17, handoff step 6). Used for abandoned sign-ups now and for withdrawal
 * and the clean-up jobs later.
 */
@Component
@ConditionalOnAuthEnabled
public class ProviderUnlinker {

	private static final Logger log = LoggerFactory.getLogger(ProviderUnlinker.class);

	private final AuthProperties properties;

	private final RestClient rest;

	ProviderUnlinker(AuthProperties properties) {
		this.properties = properties;
		this.rest = RestClient.builder().requestFactory(LoginConfiguration.providerCalls(properties.providerCalls())).build();
	}

	/** Unlinks the provider account of a pending sign-up. Returns whether the provider accepted it. */
	public boolean unlink(PendingSignup pending) {
		return switch (LoginProvider.valueOf(pending.method())) {
			case KAKAO -> unlinkKakao(pending.subject());
			case NAVER -> revokeNaver(pending.naverAccessToken());
		};
	}

	/** Kakao unlink by the user number, with the service app admin key. */
	public boolean unlinkKakao(String userId) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("target_id_type", "user_id");
		form.add("target_id", userId);
		return post(LoginProvider.KAKAO, properties.kakao().unlinkUri(),
				headers -> headers.set(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.kakao().adminKey()), form);
	}

	/** Naver revoke of an access token. Without a token (none kept) there is nothing to send. */
	public boolean revokeNaver(String accessToken) {
		if (accessToken == null || accessToken.isBlank()) {
			log.warn("Naver revoke skipped: no access token");
			return false;
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", properties.naver().clientId());
		form.add("client_secret", properties.naver().clientSecret());
		form.add("token", accessToken);
		form.add("token_type_hint", "access_token");
		return post(LoginProvider.NAVER, properties.naver().unlinkUri(), headers -> {
		}, form);
	}

	private boolean post(LoginProvider provider, String uri, Consumer<HttpHeaders> headers,
			MultiValueMap<String, String> form) {
		try {
			rest.post()
				.uri(uri)
				.headers(headers)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity();
			return true;
		}
		catch (RestClientException ex) {
			// The exception type only: messages can carry the request or response body
			log.warn("Unlinking at {} failed: {}", provider, ex.getClass().getSimpleName());
			return false;
		}
	}

}
