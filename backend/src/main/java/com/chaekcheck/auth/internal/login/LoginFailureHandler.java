package com.chaekcheck.auth.internal.login;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Turns a failed callback into a result code in the address (design-delta "결과 코드", ADR-0045).
 * <ul>
 * <li>{@code error=access_denied} from the provider (both providers send it on cancel) → {@code login_cancelled}</li>
 * <li>no saved authorization request → {@code login_expired}; a saved one with another {@code state} →
 * {@code login_state_mismatch}</li>
 * <li>any other provider error, token exchange or profile failure (including timeouts) →
 * {@code login_provider_error&provider=naver|kakao}, the provider taken from the callback path</li>
 * </ul>
 * On a withdrawal re-authentication callback ({@code -reauth}) cancel and provider errors are
 * {@code withdraw_cancelled} and {@code withdraw_provider_error}. A missing or mismatched {@code state} keeps the
 * login codes: the result code table has no withdrawal codes for them.
 */
final class LoginFailureHandler implements AuthenticationFailureHandler {

	private static final Logger log = LoggerFactory.getLogger(LoginFailureHandler.class);

	static final String CALLBACK_BASE = "/api/v1/auth/login/oauth2/code/";

	/** OAuth error codes are short lower-case words; anything else is not written to the log as is. */
	private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,64}");

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) {
		String errorCode = exception instanceof OAuth2AuthenticationException oauth
				? oauth.getError().getErrorCode() : null;
		String registrationId = registrationIdOf(request);
		boolean withdrawal = LoginProvider.isReauth(registrationId);
		if ("access_denied".equals(errorCode)) {
			LoginRedirects.complete(response, withdrawal ? "withdraw_cancelled" : "login_cancelled");
			return;
		}
		Object missing = request.getAttribute(LoginAuthorizationRequestRepository.MISSING_ATTRIBUTE);
		if (missing == LoginAuthorizationRequestRepository.Missing.EXPIRED) {
			LoginRedirects.complete(response, "login_expired");
			return;
		}
		if (missing == LoginAuthorizationRequestRepository.Missing.STATE_MISMATCH) {
			LoginRedirects.complete(response, "login_state_mismatch");
			return;
		}
		LoginProvider provider = LoginProvider.fromRegistrationId(registrationId).orElse(null);
		// The error code only, never the provider's response body or a token
		log.warn("Login with {} failed: {}", provider,
				errorCode != null && ERROR_CODE.matcher(errorCode).matches() ? errorCode : "unrecognized error");
		if (provider == null) {
			LoginRedirects.complete(response, "login_expired");
			return;
		}
		LoginRedirects.complete(response, withdrawal ? "withdraw_provider_error" : "login_provider_error", provider);
	}

	private static String registrationIdOf(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return path.startsWith(CALLBACK_BASE) ? path.substring(CALLBACK_BASE.length()) : null;
	}

}
