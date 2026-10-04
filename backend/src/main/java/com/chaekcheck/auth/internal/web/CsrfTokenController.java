package com.chaekcheck.auth.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.auth.ConditionalOnAuthEnabled;

/**
 * Gives the web a new {@code XSRF-TOKEN} cookie, before its first change request and after a {@code csrf_invalid}
 * 403. {@code csrf.spa()} writes the cookie only when a token is first needed, so this saves a new one every time.
 */
@RestController
@ConditionalOnAuthEnabled
class CsrfTokenController {

	private final CsrfTokenRepository csrfTokens;

	CsrfTokenController(CsrfTokenRepository csrfTokens) {
		this.csrfTokens = csrfTokens;
	}

	@GetMapping("/api/v1/auth/csrf-token")
	ResponseEntity<Void> csrfToken(HttpServletRequest request, HttpServletResponse response) {
		csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
		return ResponseEntity.noContent().build();
	}

}
