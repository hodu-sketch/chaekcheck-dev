package com.chaekcheck.auth.internal;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.LoginStatus;
import com.chaekcheck.auth.LoginStatusReader;

/**
 * Login off (the opposite of {@link ConditionalOnAuthEnabled}): always {@link LoginStatus#LOGIN_OFF}, without
 * looking at any cookie (ADR-0102).
 */
@Component
@ConditionalOnBooleanProperty(value = ConditionalOnAuthEnabled.PROPERTY, havingValue = false, matchIfMissing = true)
class LoginOffStatusReader implements LoginStatusReader {

	@Override
	public LoginStatus read(HttpServletRequest request) {
		return LoginStatus.LOGIN_OFF;
	}

}
