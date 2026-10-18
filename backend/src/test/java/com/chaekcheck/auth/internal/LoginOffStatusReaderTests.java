package com.chaekcheck.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.chaekcheck.auth.LoginState;

/** Login off (ADR-0102, ADR-0134 "꺼짐"): login off and no account number, whatever cookie is there. */
class LoginOffStatusReaderTests {

	@Test
	void loginOffWithoutAnAccountNumber() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie("access_token", "anything"));

		LoginState state = new LoginOffStatusReader().read(request);

		assertThat(state).isEqualTo(LoginState.LOGIN_OFF);
		assertThat(state.accountId()).isNull();
		assertThat(state.status().value()).isEqualTo("login_off");
	}

}
