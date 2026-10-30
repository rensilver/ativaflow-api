package com.rensilver.ativaflow_api.http;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class RestClientWireMockTests {

	@Test
	void restClientCanCallWireMock(WireMockRuntimeInfo wireMock) {
		stubFor(get("/compatibility").willReturn(ok("ok")));

		String response = RestClient.create(wireMock.getHttpBaseUrl())
				.get()
				.uri("/compatibility")
				.retrieve()
				.body(String.class);

		assertThat(response).isEqualTo("ok");
	}

}
