package com.chaekcheck.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.chaekcheck.auth.internal.account.AccountStore;

import tools.jackson.databind.json.JsonMapper;

/** A database failure while checking the account is our error (500), never "not signed in" (401). */
class TokenCheckFailureTests {

	private final AccountStore accounts = mock(AccountStore.class);

	private final AccountJwtAuthenticationConverter converter = new AccountJwtAuthenticationConverter(accounts);

	private final ProblemResponses problems = new ProblemResponses(JsonMapper.builder().build());

	@Test
	void databaseFailureIsAServerError() throws Exception {
		given(accounts.exists(7)).willThrow(new DataAccessResourceFailureException("connection refused"));

		assertThatThrownBy(() -> converter.convert(jwt("7"))).isInstanceOf(AuthenticationServiceException.class);

		MockHttpServletResponse response = new MockHttpServletResponse();
		problems.onAuthenticationFailure(new MockHttpServletRequest("GET", "/api/v1/me"), response,
				new AuthenticationServiceException("Account check failed"));
		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getContentAsString()).contains("\"code\":\"internal_error\"")
			.doesNotContain("connection refused");
	}

	@Test
	void rejectedTokenIsUnauthorized() throws Exception {
		given(accounts.exists(7)).willReturn(false);

		assertThatThrownBy(() -> converter.convert(jwt("7"))).isInstanceOf(InvalidBearerTokenException.class);

		MockHttpServletResponse response = new MockHttpServletResponse();
		problems.onAuthenticationFailure(new MockHttpServletRequest("GET", "/api/v1/me"), response,
				new InvalidBearerTokenException("Account not found"));
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("\"code\":\"unauthorized\"")
			.doesNotContain("Account not found");
	}

	private static Jwt jwt(String subject) {
		return Jwt.withTokenValue("token")
			.header("alg", "HS256")
			.subject(subject)
			.expiresAt(Instant.now().plusSeconds(60))
			.build();
	}

}
