package com.chaekcheck.auth.internal.web;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.account.AccountStore;
import com.chaekcheck.auth.internal.account.Withdrawal;
import com.chaekcheck.auth.internal.login.PendingSignup;
import com.chaekcheck.auth.internal.login.ReauthenticationProof;
import com.chaekcheck.auth.internal.token.TokenCookies;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The signed-in account. The web knows whether login is on and whether someone is signed in only from this
 * address: 404 off, 401 not signed in, 200 signed in (user decision 2026-10-04). {@code loginMethods} is not shown
 * on screen (C23).
 */
@RestController
@ConditionalOnAuthEnabled
class MeController {

	private final AccountStore accounts;

	private final Withdrawal withdrawal;

	MeController(AccountStore accounts, Withdrawal withdrawal) {
		this.accounts = accounts;
		this.withdrawal = withdrawal;
	}

	@GetMapping("/api/v1/me")
	Me me(@AuthenticationPrincipal Jwt token) {
		long accountId = Long.parseLong(token.getSubject());
		return accounts.find(accountId)
			.map(account -> new Me(account.id(), account.loginMethods(),
					account.consents()
						.stream()
						.map(consent -> new Consent(consent.type(), consent.version(), consent.agreedAt()))
						.toList()))
			// Deleted between the token check and here
			.orElseThrow(MeController::unauthorized);
	}

	/**
	 * Withdrawal (step 8): needs the access cookie, the CSRF token and a re-authentication younger than 5 minutes in
	 * the session. 204 with both login cookies cleared; 403 {@code reauthentication_required} /
	 * {@code reauthentication_expired}; a failure of our own deletion is 500 {@code internal_error}.
	 */
	@DeleteMapping("/api/v1/me")
	ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		ReauthenticationProof proof = null;
		if (session != null) {
			proof = (ReauthenticationProof) session.getAttribute(ReauthenticationProof.SESSION_ATTRIBUTE);
			// One use: taken before anything happens, so a second request with it is 403 (security review 2026-10-04).
			// A pending sign-up in the same session must not recreate the account afterwards.
			session.removeAttribute(ReauthenticationProof.SESSION_ATTRIBUTE);
			session.removeAttribute(PendingSignup.SESSION_ATTRIBUTE);
		}
		try {
			withdrawal.withdraw(Long.parseLong(token.getSubject()), proof);
		}
		catch (Withdrawal.ReauthenticationException ex) {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Sign in again to withdraw");
			problem.setProperty("code", ex.code());
			throw new ErrorResponseException(HttpStatus.FORBIDDEN, problem, ex);
		}
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE,
					TokenCookies.cleared().stream().map(ResponseCookie::toString).toArray(String[]::new))
			.build();
	}

	private static ErrorResponseException unauthorized() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Login required");
		problem.setProperty("code", "unauthorized");
		return new ErrorResponseException(HttpStatus.UNAUTHORIZED, problem, null);
	}

	record Me(long id, List<String> loginMethods, List<Consent> consents) {
	}

	/** {@code version} is left out for AGE_OVER_14, which has no document. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Consent(String type, String version, Instant agreedAt) {
	}

}
