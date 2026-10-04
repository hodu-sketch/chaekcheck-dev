package com.chaekcheck.auth.internal.web;

import java.util.Arrays;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.login.LoginProvider;

/**
 * Which login buttons the web shows (AC-22): the providers in {@code chaekcheck.auth.providers}, e.g.
 * {@code ["KAKAO"]}. Public, no CSRF (GET). While login is off this address does not exist (404), like every other
 * login address (user decision 2026-10-04: "공개 API 하나").
 */
@RestController
@ConditionalOnAuthEnabled
class ProvidersController {

	private final List<String> providers;

	ProvidersController(AuthProperties properties) {
		this.providers = Arrays.stream(LoginProvider.values()).filter(properties::enabled).map(LoginProvider::method).toList();
	}

	@GetMapping("/api/v1/auth/providers")
	List<String> providers() {
		return providers;
	}

}
