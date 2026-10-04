package com.chaekcheck.auth.internal.account;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.login.LoginProvider;
import com.chaekcheck.auth.internal.login.ProviderUnlinker;
import com.chaekcheck.auth.internal.login.ReauthenticationProof;

/**
 * Withdrawal ({@code DELETE /api/v1/me}, handoff step 8). Needs a re-authentication proof for the same account,
 * younger than 5 minutes. Then the provider link is ended with one request (Kakao unlink with the admin key, Naver
 * revoke with the token from the re-authentication); a failure there is only logged and the withdrawal goes on.
 * Last, the account is deleted in the one place for it ({@link AccountDeletion}).
 */
@Service
@ConditionalOnAuthEnabled
public class Withdrawal {

	private final AccountStore accounts;

	private final AccountDeletion deletion;

	private final ProviderUnlinker unlinker;

	private final Duration proofTtl;

	private final Clock clock;

	@Autowired
	Withdrawal(AccountStore accounts, AccountDeletion deletion, ProviderUnlinker unlinker, AuthProperties properties) {
		this(accounts, deletion, unlinker, properties.tokens().reauthenticationTtl(), Clock.systemUTC());
	}

	Withdrawal(AccountStore accounts, AccountDeletion deletion, ProviderUnlinker unlinker, Duration proofTtl,
			Clock clock) {
		this.accounts = accounts;
		this.deletion = deletion;
		this.unlinker = unlinker;
		this.proofTtl = proofTtl;
		this.clock = clock;
	}

	/**
	 * @param accountId the account of the access token
	 * @param proof the proof from the session, or {@code null}
	 * @throws ReauthenticationException {@code reauthentication_required} (no proof, or a proof for another account)
	 * or {@code reauthentication_expired} (older than 5 minutes)
	 */
	public void withdraw(long accountId, ReauthenticationProof proof) {
		if (proof == null || proof.accountId() != accountId) {
			throw new ReauthenticationException("reauthentication_required");
		}
		if (!clock.instant().isBefore(proof.verifiedAt().plus(proofTtl))) {
			throw new ReauthenticationException("reauthentication_expired");
		}
		switch (LoginProvider.valueOf(proof.method())) {
			case KAKAO -> accounts.subjectOf(accountId, proof.method()).ifPresent(unlinker::unlinkKakao);
			case NAVER -> unlinker.revokeNaver(proof.naverAccessToken());
		}
		deletion.delete(accountId);
	}

	/** 403 with {@link #code()}: the web restarts the re-authentication (C25). */
	public static final class ReauthenticationException extends RuntimeException {

		private final String code;

		ReauthenticationException(String code) {
			super(code);
			this.code = code;
		}

		public String code() {
			return code;
		}

	}

}
