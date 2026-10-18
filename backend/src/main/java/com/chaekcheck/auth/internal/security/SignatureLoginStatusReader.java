package com.chaekcheck.auth.internal.security;

import javax.crypto.SecretKey;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.LoginState;
import com.chaekcheck.auth.LoginStatus;
import com.chaekcheck.auth.LoginStatusReader;

/**
 * Login on: {@link LoginStatus#SIGNED_IN} when the {@code access_token} cookie carries our HS256 signature, expired
 * or not (ADR-0102), with the account number from the token's subject (ADR-0134). Its own decoder checks the
 * signature and nothing else; the login decoder ({@code SecurityConfiguration#jwtDecoder}) keeps rejecting expired
 * tokens. The account is not looked up.
 */
@Component
@ConditionalOnAuthEnabled
class SignatureLoginStatusReader implements LoginStatusReader {

	private final JwtDecoder signatureOnly;

	SignatureLoginStatusReader(SecretKey accessTokenKey) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(accessTokenKey).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
		this.signatureOnly = decoder;
	}

	@Override
	public LoginState read(HttpServletRequest request) {
		String token = accessToken(request);
		if (token == null) {
			return LoginState.SIGNED_OUT;
		}
		try {
			Jwt jwt = signatureOnly.decode(token);
			return LoginState.signedIn(accountId(jwt.getSubject()));
		}
		catch (RuntimeException ex) {
			// Broken, unsigned or signed with another key; the token value is never logged (C6)
			return LoginState.SIGNED_OUT;
		}
	}

	/** The subject as {@code TokenService} writes it (the account number as digits), else null. */
	private static Long accountId(String subject) {
		if (subject == null || subject.isEmpty() || subject.length() > 18) {
			return null;
		}
		for (int i = 0; i < subject.length(); i++) {
			char c = subject.charAt(i);
			if (c < '0' || c > '9') {
				return null;
			}
		}
		long id = Long.parseLong(subject);
		return id > 0 ? id : null;
	}

	private static String accessToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (AuthCookies.ACCESS_TOKEN.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
