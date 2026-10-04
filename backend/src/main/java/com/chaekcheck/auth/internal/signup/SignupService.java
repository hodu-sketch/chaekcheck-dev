package com.chaekcheck.auth.internal.signup;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.account.AccountStore;
import com.chaekcheck.auth.internal.account.AccountStore.NewConsent;
import com.chaekcheck.auth.internal.login.PendingSignup;
import com.chaekcheck.auth.internal.token.TokenService;
import com.chaekcheck.auth.internal.token.TokenService.LoginCookies;

/**
 * Sign-up from a pending sign-up and the three consents (handoff step 6, AC-2). The document versions in
 * {@code chaekcheck.auth.documents} are the source (C27).
 */
@Service
@ConditionalOnAuthEnabled
public class SignupService {

	private final AccountStore accounts;

	private final TokenService tokens;

	private final TransactionTemplate transaction;

	private final AuthProperties.Documents documents;

	private final Clock clock = Clock.systemUTC();

	SignupService(AccountStore accounts, TokenService tokens, TransactionTemplate transaction,
			AuthProperties properties) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.transaction = transaction;
		this.documents = properties.documents();
	}

	/** The documents to agree to, with their current versions ({@code GET /api/v1/auth/signup}). */
	public List<Document> documents() {
		return List.of(new Document(ConsentType.TERMS_OF_SERVICE, documents.termsOfServiceVersion()),
				new Document(ConsentType.PRIVACY_COLLECTION, documents.privacyCollectionVersion()));
	}

	/**
	 * Checks the consents: every one of the three must be there (missing → {@link MissingConsentsException}), and the
	 * two documents must carry the current versions (otherwise {@link DocumentVersionMismatchException}).
	 */
	public List<NewConsent> check(Map<ConsentType, Optional<String>> given) {
		List<String> missing = new ArrayList<>();
		for (ConsentType type : ConsentType.values()) {
			if (!given.containsKey(type)) {
				missing.add(type.name());
			}
		}
		if (!missing.isEmpty()) {
			throw new MissingConsentsException(missing);
		}
		List<NewConsent> consents = new ArrayList<>();
		for (Document document : documents()) {
			String version = given.get(document.type()).orElse(null);
			if (!document.version().equals(version)) {
				throw new DocumentVersionMismatchException();
			}
			consents.add(new NewConsent(document.type().name(), version));
		}
		consents.add(new NewConsent(ConsentType.AGE_OVER_14.name(), null));
		return consents;
	}

	/**
	 * Creates the account, login method, three consents and a refresh row in one transaction and returns the login
	 * cookies. A second sign-up with the same provider number (double click, two tabs) signs in to the account the
	 * first one created instead of failing (C26).
	 */
	public LoginCookies signUp(PendingSignup pending, List<NewConsent> consents) {
		Instant now = clock.instant();
		try {
			return transaction.execute(status -> accounts.findByLoginMethod(pending.method(), pending.subject())
				.map(existing -> signInExisting(existing, now))
				.orElseGet(() -> tokens.signIn(accounts.create(pending.method(), pending.subject(), consents, now))));
		}
		catch (DuplicateKeyException ex) {
			// Two sign-ups at the same moment: the other one created the account first
			long existing = accounts.findByLoginMethod(pending.method(), pending.subject())
				.orElseThrow(() -> new IllegalStateException("Login method conflict without a row", ex));
			return transaction.execute(status -> signInExisting(existing, now));
		}
	}

	private LoginCookies signInExisting(long accountId, Instant now) {
		accounts.touchLastLogin(accountId, now);
		return tokens.signIn(accountId);
	}

	public enum ConsentType {

		TERMS_OF_SERVICE, PRIVACY_COLLECTION, AGE_OVER_14

	}

	public record Document(ConsentType type, String version) {
	}

	/** 400 {@code validation_error} with one {@code required} entry per missing consent. */
	public static final class MissingConsentsException extends RuntimeException {

		private final List<String> missing;

		MissingConsentsException(List<String> missing) {
			super("Missing consents: " + missing);
			this.missing = List.copyOf(missing);
		}

		public List<String> missing() {
			return missing;
		}

	}

	/** 409 {@code document_version_mismatch}: the documents changed after the screen loaded them (C24). */
	public static final class DocumentVersionMismatchException extends RuntimeException {

		DocumentVersionMismatchException() {
			super("Document version mismatch");
		}

	}

}
