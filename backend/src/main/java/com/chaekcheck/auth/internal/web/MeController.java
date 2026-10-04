package com.chaekcheck.auth.internal.web;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.account.AccountStore;
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

	MeController(AccountStore accounts) {
		this.accounts = accounts;
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
