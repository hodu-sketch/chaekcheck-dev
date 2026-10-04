package com.chaekcheck.auth.internal.login;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.chaekcheck.auth.internal.account.AccountStore;
import com.chaekcheck.auth.internal.security.AuthCookies;
import com.chaekcheck.auth.internal.token.TokenService;
import com.chaekcheck.auth.internal.token.TokenService.LoginCookies;

/**
 * After the provider confirmed who the user is.
 * <p>
 * Login ({@code naver}, {@code kakao}, handoff step 5):
 * <ul>
 * <li>known provider number (R3): {@code last_login_at} is updated, both login cookies are issued with the same code
 * as sign-up, then {@code /auth/complete?result=login_success}</li>
 * <li>unknown: a {@link PendingSignup} goes into the session and the browser goes to {@code /signup}; nothing is
 * written to the database</li>
 * </ul>
 * Withdrawal re-authentication ({@code naver-reauth}, {@code kakao-reauth}, step 8): the browser's refresh cookie
 * says which account is signed in; if the provider account belongs to it, a {@link ReauthenticationProof} goes into
 * the session ({@code withdraw_reauthenticated}). No cookies, no refresh row, no {@code last_login_at} change.
 * <p>
 * The Spring authentication is not kept anywhere: the login is the token cookies (C15).
 */
final class LoginSuccessHandler implements AuthenticationSuccessHandler {

	private final AccountStore accounts;

	private final TokenService tokens;

	private final Clock clock;

	LoginSuccessHandler(AccountStore accounts, TokenService tokens, Clock clock) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.clock = clock;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) {
		OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
		String registrationId = token.getAuthorizedClientRegistrationId();
		LoginProvider provider = LoginProvider.fromRegistrationId(registrationId).orElseThrow();
		String subject = token.getPrincipal().getName();
		String naverAccessToken = (String) request
			.getAttribute(NonStoringAuthorizedClientRepository.NAVER_ACCESS_TOKEN_ATTRIBUTE);
		Instant now = clock.instant();
		if (LoginProvider.isReauth(registrationId)) {
			reauthenticated(request, response, provider, subject, naverAccessToken, now);
		}
		else {
			signedIn(request, response, provider, subject, naverAccessToken, now);
		}
	}

	private void signedIn(HttpServletRequest request, HttpServletResponse response, LoginProvider provider,
			String subject, String naverAccessToken, Instant now) {
		Optional<Long> account = accounts.findByLoginMethod(provider.method(), subject);
		HttpSession session = request.getSession();
		session.removeAttribute(PendingSignup.SESSION_ATTRIBUTE);
		if (account.isPresent()) {
			accounts.touchLastLogin(account.get(), now);
			LoginCookies cookies = tokens.signIn(account.get());
			response.addHeader(HttpHeaders.SET_COOKIE, cookies.access().toString());
			response.addHeader(HttpHeaders.SET_COOKIE, cookies.refresh().toString());
			LoginRedirects.complete(response, "login_success");
			return;
		}
		session.setAttribute(PendingSignup.SESSION_ATTRIBUTE,
				new PendingSignup(provider.method(), subject, now, naverAccessToken));
		LoginRedirects.to(response, LoginRedirects.SIGNUP);
	}

	private void reauthenticated(HttpServletRequest request, HttpServletResponse response, LoginProvider provider,
			String subject, String naverAccessToken, Instant now) {
		Optional<Long> current = tokens.accountOfRefresh(refreshCookie(request));
		if (current.isEmpty()) {
			LoginRedirects.complete(response, "withdraw_login_expired");
			return;
		}
		Optional<Long> returned = accounts.findByLoginMethod(provider.method(), subject);
		if (!returned.equals(current)) {
			// Signed in again with another provider account than the one being withdrawn (AC-10)
			LoginRedirects.complete(response, "withdraw_account_mismatch");
			return;
		}
		request.getSession()
			.setAttribute(ReauthenticationProof.SESSION_ATTRIBUTE,
					new ReauthenticationProof(current.get(), provider.method(), now, naverAccessToken));
		LoginRedirects.complete(response, "withdraw_reauthenticated");
	}

	private static String refreshCookie(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.getCookies()) {
			if (AuthCookies.REFRESH_TOKEN.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
