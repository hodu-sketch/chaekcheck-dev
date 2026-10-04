package com.chaekcheck.auth.internal.login;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Kakao's "사용자 목록 가져오기" ({@code GET /v1/user/ids}, admin key): every user number linked to the app, read page
 * by page ({@code limit=100}, {@code order=asc}, {@code from_id} = last number of the page before) until
 * {@code after_url} is null. Calls are spaced to stay below 100 a minute. Any failed page, a page without new
 * numbers that still says there is more, or a cancelled wait means the list is not complete: empty is returned and
 * the caller must not delete anything (C29).
 */
@Component
@ConditionalOnAuthEnabled
public class KakaoLinkedUsers {

	private static final Logger log = LoggerFactory.getLogger(KakaoLinkedUsers.class);

	private static final int PAGE_SIZE = 100;

	private final AuthProperties properties;

	private final RestClient rest;

	KakaoLinkedUsers(AuthProperties properties) {
		this.properties = properties;
		this.rest = RestClient.builder().requestFactory(LoginConfiguration.providerCalls(properties.providerCalls())).build();
	}

	/** All linked user numbers, or empty when the list could not be read to the end. */
	public Optional<Set<String>> all() {
		Duration interval = properties.cleanup().kakaoCallInterval();
		Set<String> ids = new HashSet<>();
		String fromId = null;
		int pages = 0;
		while (true) {
			if (pages > 0 && !pause(interval)) {
				return Optional.empty();
			}
			Page page;
			try {
				page = rest.get()
					.uri(uri(fromId))
					.header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.kakao().adminKey())
					.retrieve()
					.body(Page.class);
			}
			catch (RestClientException ex) {
				// The type only: messages can carry the response body
				log.warn("Kakao user list stopped at page {}: {}", pages + 1, ex.getClass().getSimpleName());
				return Optional.empty();
			}
			pages++;
			if (page == null || page.elements() == null) {
				log.warn("Kakao user list page {} had no elements", pages);
				return Optional.empty();
			}
			int before = ids.size();
			page.elements().forEach(id -> ids.add(String.valueOf(id)));
			if (page.afterUrl() == null || page.elements().isEmpty()) {
				return Optional.of(ids);
			}
			if (ids.size() == before) {
				log.warn("Kakao user list page {} brought no new numbers but says there is more", pages);
				return Optional.empty();
			}
			fromId = String.valueOf(page.elements().getLast());
		}
	}

	private String uri(String fromId) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(properties.kakao().userIdsUri())
			.queryParam("limit", PAGE_SIZE)
			.queryParam("order", "asc");
		if (fromId != null) {
			uri.queryParam("from_id", fromId);
		}
		return uri.toUriString();
	}

	private static boolean pause(Duration interval) {
		if (interval.isZero()) {
			return true;
		}
		try {
			Thread.sleep(interval);
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** One page; the numbers are read as Long (they are up to 19 digits). */
	record Page(List<Long> elements, @JsonProperty("after_url") String afterUrl) {
	}

}
