package com.chaekcheck.auth.internal.login;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.chaekcheck.auth.internal.account.AccountStore;
import com.chaekcheck.auth.internal.token.TokenService;
import com.chaekcheck.auth.internal.token.TokenService.LoginCookies;

/**
 * After the provider confirmed who the user is (handoff step 5).
 * <ul>
 * <li>Known provider number (R3): {@code last_login_at} is updated, both login cookies are issued with the same
 * code as sign-up, then {@code /auth/complete?result=login_success}.</li>
 * <li>Unknown: a {@link PendingSignup} goes into the session and the browser goes to {@code /signup}. Nothing is
 * written to the database.</li>
 * </ul>
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
		LoginProvider provider = LoginProvider.fromRegistrationId(token.getAuthorizedClientRegistrationId())
			.orElseThrow();
		String subject = token.getPrincipal().getName();
		String naverAccessToken = (String) request
			.getAttribute(NonStoringAuthorizedClientRepository.NAVER_ACCESS_TOKEN_ATTRIBUTE);
		Instant now = clock.instant();

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

}
