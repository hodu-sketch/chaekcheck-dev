package com.chaekcheck.auth.internal.login;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * 404 {@code not_found} for a login start or callback address whose registration is not offered: a provider left
 * out of {@code chaekcheck.auth.providers} (with its {@code -reauth} registration, AC-22) or a name that does not
 * exist. Runs before Spring's OAuth2 filters, which would otherwise answer a callback with a login result code.
 * The body has the same members as the application's other 404s.
 */
public final class ProviderPathFilter extends OncePerRequestFilter {

	private static final String START = LoginConfiguration.AUTHORIZATION_BASE + "/";

	private static final String CALLBACK = LoginFailureHandler.CALLBACK_BASE;

	private final Set<String> offered;

	private final JsonMapper json;

	ProviderPathFilter(Set<String> offered, JsonMapper json) {
		this.offered = Set.copyOf(offered);
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		String registrationId = path.startsWith(START) ? path.substring(START.length())
				: path.startsWith(CALLBACK) ? path.substring(CALLBACK.length()) : null;
		if (registrationId != null && !offered.contains(registrationId)) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("title", HttpStatus.NOT_FOUND.getReasonPhrase());
			body.put("status", HttpStatus.NOT_FOUND.value());
			body.put("detail", "No such login provider");
			body.put("instance", request.getRequestURI());
			body.put("code", "not_found");
			response.setStatus(HttpStatus.NOT_FOUND.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			json.writeValue(response.getOutputStream(), body);
			return;
		}
		chain.doFilter(request, response);
	}

}
