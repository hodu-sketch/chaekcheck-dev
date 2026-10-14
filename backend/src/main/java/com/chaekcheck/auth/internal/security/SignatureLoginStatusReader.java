package com.chaekcheck.auth.internal.security;

import javax.crypto.SecretKey;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.LoginStatus;
import com.chaekcheck.auth.LoginStatusReader;

/**
 * Login on: {@link LoginStatus#SIGNED_IN} when the {@code access_token} cookie carries our HS256 signature, expired
 * or not (ADR-0102). Its own decoder checks the signature and nothing else; the login decoder
 * ({@code SecurityConfiguration#jwtDecoder}) keeps rejecting expired tokens. The account is not looked up.
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
	public LoginStatus read(HttpServletRequest request) {
		String token = accessToken(request);
		if (token == null) {
			return LoginStatus.SIGNED_OUT;
		}
		try {
			signatureOnly.decode(token);
			return LoginStatus.SIGNED_IN;
		}
		catch (RuntimeException ex) {
			// Broken, unsigned or signed with another key; the token value is never logged (C6)
			return LoginStatus.SIGNED_OUT;
		}
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
