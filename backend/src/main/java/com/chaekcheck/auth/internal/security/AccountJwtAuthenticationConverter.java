package com.chaekcheck.auth.internal.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.chaekcheck.auth.internal.account.AccountStore;

/**
 * Accepts a valid token only while its account still exists, so the tokens of a deleted account stop at once
 * instead of after 15 minutes (ADR-0038). The account number is the {@code sub} claim; there are no authorities.
 */
final class AccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final AccountStore accounts;

	AccountJwtAuthenticationConverter(AccountStore accounts) {
		this.accounts = accounts;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		long accountId;
		try {
			accountId = Long.parseLong(String.valueOf(jwt.getSubject()));
		}
		catch (NumberFormatException ex) {
			throw new InvalidBearerTokenException("Invalid subject");
		}
		if (accountId <= 0 || !exists(accountId)) {
			throw new InvalidBearerTokenException("Account not found");
		}
		return new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject());
	}

	/** A database failure is our error (500), not "not signed in": a 401 would make the web drop the login. */
	private boolean exists(long accountId) {
		try {
			return accounts.exists(accountId);
		}
		catch (DataAccessException ex) {
			throw new AuthenticationServiceException("Account check failed", ex);
		}
	}

}
