package com.chaekcheck.auth.internal.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.csrf.CsrfException;

import tools.jackson.databind.json.JsonMapper;

/**
 * 401 and 403 from the security filters as ProblemDetail with a {@code code} (ADR-0008). These errors happen before
 * Spring MVC, so the root {@code ApiExceptionHandler} never sees them (handoff C2). Same members as the MVC errors.
 */
final class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler, AuthenticationFailureHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemResponses.class);

	private final JsonMapper json;

	ProblemResponses(JsonMapper json) {
		this.json = json;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		write(request, response, HttpStatus.UNAUTHORIZED, "Login required", "unauthorized");
	}

	/** A token that was sent but rejected. A failure on our side (database) is a 500, not a 401. */
	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		if (exception instanceof AuthenticationServiceException) {
			log.error("Token check failed", exception);
			write(request, response, HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", "internal_error");
		}
		else {
			commence(request, response, exception);
		}
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		if (accessDeniedException instanceof CsrfException) {
			// The web gets a new token and tries once more (design-delta API notes)
			write(request, response, HttpStatus.FORBIDDEN, "Invalid or missing CSRF token", "csrf_invalid");
		}
		else {
			write(request, response, HttpStatus.FORBIDDEN, "Access denied", "forbidden");
		}
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail,
			String code) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", status.getReasonPhrase());
		body.put("status", status.value());
		body.put("detail", detail);
		body.put("instance", request.getRequestURI());
		body.put("code", code);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		json.writeValue(response.getOutputStream(), body);
	}

}
