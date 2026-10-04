package com.chaekcheck.auth;

/** Number of an account ({@code auth.account.id}). Other modules key their own data by it (cycle 06). */
public record AccountId(long value) {

	public AccountId {
		if (value <= 0) {
			throw new IllegalArgumentException("Account id must be positive: " + value);
		}
	}

}
