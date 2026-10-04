package com.chaekcheck.auth.internal.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * Keeps the authorization request (with its {@code state}) in the session between "go to the provider" and the
 * callback. Spring's own session repository answers "not found" both when nothing was saved and when the
 * {@code state} differs; this one notes which of the two happened, so the callback can send
 * {@code login_expired} or {@code login_state_mismatch} (design-delta result codes).
 */
final class LoginAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	static final String SESSION_ATTRIBUTE = LoginAuthorizationRequestRepository.class.getName() + ".request";

	/** Request attribute set when the callback finds no usable request: {@link Missing}. */
	static final String MISSING_ATTRIBUTE = LoginAuthorizationRequestRepository.class.getName() + ".missing";

	enum Missing {

		/** No request in the session: the session ended (10 minutes) or the login was started elsewhere. */
		EXPIRED,
		/** A request is saved but its {@code state} is not the one the callback carries. */
		STATE_MISMATCH

	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		OAuth2AuthorizationRequest saved = saved(request);
		return saved != null && saved.getState().equals(request.getParameter(OAuth2ParameterNames.STATE)) ? saved
				: null;
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
			HttpServletResponse response) {
		if (authorizationRequest == null) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.removeAttribute(SESSION_ATTRIBUTE);
			}
			return;
		}
		request.getSession().setAttribute(SESSION_ATTRIBUTE, authorizationRequest);
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		OAuth2AuthorizationRequest saved = saved(request);
		if (saved == null) {
			request.setAttribute(MISSING_ATTRIBUTE, Missing.EXPIRED);
			return null;
		}
		if (!saved.getState().equals(request.getParameter(OAuth2ParameterNames.STATE))) {
			// Leave the saved request: the real callback for it may still come
			request.setAttribute(MISSING_ATTRIBUTE, Missing.STATE_MISMATCH);
			return null;
		}
		request.getSession().removeAttribute(SESSION_ATTRIBUTE);
		return saved;
	}

	private static OAuth2AuthorizationRequest saved(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		return session == null ? null : (OAuth2AuthorizationRequest) session.getAttribute(SESSION_ATTRIBUTE);
	}

}
