package com.chaekcheck.auth.internal.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.chaekcheck.auth.AccountId;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;

/**
 * Fills an {@link AccountId} parameter of any controller method from the signed-in account (the {@code sub} of the
 * access token), so other modules get the account without knowing Spring Security or this module's internals (cycle
 * 06, C10). Only {@code /api/v1/me/**} has a login (C9): elsewhere, or without a valid token, it is 401
 * {@code unauthorized}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuthEnabled
class AccountIdArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.getParameterType() == AccountId.class;
	}

	@Override
	public AccountId resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
			// The token converter has already checked that the subject is a positive number of a live account
			return new AccountId(Long.parseLong(token.getToken().getSubject()));
		}
		throw unauthorized();
	}

	private static ErrorResponseException unauthorized() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Login required");
		problem.setProperty("code", "unauthorized");
		return new ErrorResponseException(HttpStatus.UNAUTHORIZED, problem, null);
	}

}
