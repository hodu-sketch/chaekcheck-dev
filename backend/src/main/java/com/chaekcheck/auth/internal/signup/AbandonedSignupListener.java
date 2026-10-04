package com.chaekcheck.auth.internal.signup;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import org.springframework.stereotype.Component;

import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.login.PendingSignup;
import com.chaekcheck.auth.internal.login.ProviderUnlinker;

/**
 * When a session ends (10 minutes) with a pending sign-up still in it, the user never agreed: the provider link is
 * ended the same way as a cancelled sign-up (C17). Sign-up and cancel remove the pending sign-up first, so it is not
 * unlinked twice. Runs on the container's session-expiry thread; a failure is only logged. Accepted limit: sessions
 * lost in a server restart are not unlinked.
 */
@Component
@ConditionalOnAuthEnabled
class AbandonedSignupListener implements HttpSessionListener {

	private final ProviderUnlinker unlinker;

	AbandonedSignupListener(ProviderUnlinker unlinker) {
		this.unlinker = unlinker;
	}

	@Override
	public void sessionDestroyed(HttpSessionEvent event) {
		if (event.getSession().getAttribute(PendingSignup.SESSION_ATTRIBUTE) instanceof PendingSignup pending) {
			unlinker.unlink(pending);
		}
	}

}
