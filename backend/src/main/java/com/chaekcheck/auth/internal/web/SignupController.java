package com.chaekcheck.auth.internal.web;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.login.PendingSignup;
import com.chaekcheck.auth.internal.login.ProviderUnlinker;
import com.chaekcheck.auth.internal.signup.SignupService;
import com.chaekcheck.auth.internal.signup.SignupService.ConsentType;
import com.chaekcheck.auth.internal.signup.SignupService.DocumentVersionMismatchException;
import com.chaekcheck.auth.internal.signup.SignupService.MissingConsentsException;
import com.chaekcheck.auth.internal.token.TokenService.LoginCookies;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The consent screen's API (S6, handoff step 6). Every call needs the pending sign-up in the session; without it
 * the answer is 404 {@code signup_not_found} (the 10 minutes passed, or the sign-up already finished).
 */
@RestController
@ConditionalOnAuthEnabled
class SignupController {

	private final SignupService signups;

	private final ProviderUnlinker unlinker;

	private final Duration pendingTtl;

	SignupController(SignupService signups, ProviderUnlinker unlinker, AuthProperties properties) {
		this.signups = signups;
		this.unlinker = unlinker;
		this.pendingTtl = properties.tokens().pendingSignupTtl();
	}

	/** Which provider and which document versions the screen shows. */
	@GetMapping("/api/v1/auth/signup")
	SignupInfo signupInfo(HttpServletRequest request) {
		PendingSignup pending = pending(request);
		return new SignupInfo(pending.method(), signups.documents()
			.stream()
			.map(document -> new DocumentVersion(document.type().name(), document.version()))
			.toList());
	}

	/**
	 * 201 with both login cookies and no {@code Location} (there is no address to read the account back, C7). 400
	 * {@code validation_error} when a consent is missing, 409 {@code document_version_mismatch} when a version is not
	 * the current one.
	 */
	@PostMapping("/api/v1/auth/signup")
	ResponseEntity<Void> signUp(@RequestBody SignupRequest body, HttpServletRequest request) {
		PendingSignup pending = pending(request);
		Map<ConsentType, Optional<String>> given = new EnumMap<>(ConsentType.class);
		for (ConsentRequest consent : body.consents() == null ? List.<ConsentRequest>of() : body.consents()) {
			if (consent != null && consent.type() != null) {
				parse(consent.type()).ifPresent(type -> given.put(type, Optional.ofNullable(consent.version())));
			}
		}
		LoginCookies cookies;
		try {
			cookies = signups.signUp(pending, signups.check(given));
		}
		catch (MissingConsentsException ex) {
			throw missingConsents(ex.missing());
		}
		catch (DocumentVersionMismatchException ex) {
			throw problem(HttpStatus.CONFLICT, "document_version_mismatch", "The documents have changed");
		}
		request.getSession().removeAttribute(PendingSignup.SESSION_ATTRIBUTE);
		return ResponseEntity.status(HttpStatus.CREATED)
			.header(HttpHeaders.SET_COOKIE, cookies.access().toString(), cookies.refresh().toString())
			.build();
	}

	/** The user declined on the consent screen: the pending sign-up is dropped and the provider link ended (AC-3). */
	@DeleteMapping("/api/v1/auth/signup")
	ResponseEntity<Void> cancel(HttpServletRequest request) {
		PendingSignup pending = pending(request);
		request.getSession().removeAttribute(PendingSignup.SESSION_ATTRIBUTE);
		// A failed unlink is logged by the unlinker and still answers 204
		unlinker.unlink(pending);
		return ResponseEntity.noContent().build();
	}

	/**
	 * The pending sign-up, if it is younger than 10 minutes counted from the provider login. An older one is dropped
	 * (security review 2026-10-04: an old tab must not recreate a withdrawn account without a new provider login).
	 */
	private PendingSignup pending(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(PendingSignup.SESSION_ATTRIBUTE) instanceof PendingSignup pending) {
			if (Instant.now().isBefore(pending.createdAt().plus(pendingTtl))) {
				return pending;
			}
			session.removeAttribute(PendingSignup.SESSION_ATTRIBUTE);
		}
		throw problem(HttpStatus.NOT_FOUND, "signup_not_found", "No sign-up in progress");
	}

	private static Optional<ConsentType> parse(String type) {
		for (ConsentType value : ConsentType.values()) {
			if (value.name().equals(type)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}

	private static ErrorResponseException missingConsents(List<String> missing) {
		ErrorResponseException ex = problem(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed");
		ex.getBody()
			.setProperty("errors", missing.stream()
				.map(type -> Map.of("field", "consents", "message", type + " is required", "code", "required"))
				.toList());
		return ex;
	}

	private static ErrorResponseException problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return new ErrorResponseException(status, problem, null);
	}

	record SignupInfo(String provider, List<DocumentVersion> documents) {
	}

	record DocumentVersion(String type, String version) {
	}

	record SignupRequest(List<ConsentRequest> consents) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ConsentRequest(String type, String version) {
	}

}
