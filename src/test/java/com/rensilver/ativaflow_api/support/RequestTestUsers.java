package com.rensilver.ativaflow_api.support;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor;

/** Consumidores com os escopos necessários para os testes de comportamento dos endpoints. */
public final class RequestTestUsers {

	private RequestTestUsers() {
	}

	public static UserRequestPostProcessor user(String consumer) {
		return SecurityMockMvcRequestPostProcessors.user(consumer).authorities(
				new SimpleGrantedAuthority("SCOPE_requests/read"),
				new SimpleGrantedAuthority("SCOPE_requests/write"));
	}
}
