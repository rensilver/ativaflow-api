package com.rensilver.ativaflow_api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtivaflowApiApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"/v3/api-docs",
			"/v3/api-docs.yaml",
			"/v3/api-docs/swagger-config",
			"/swagger-ui/index.html",
			"/swagger-ui/swagger-ui-bundle.js",
			"/swagger-ui/swagger-initializer.js"
	})
	void documentationIsAccessibleWithoutAuthentication(@NonNull String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().isOk());
	}

	@Test
	void openApiDescribesTheApplication() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
				.andExpect(jsonPath("$.info.title").value("AtivaFlow API"))
				.andExpect(jsonPath("$.info.version").value("v1"));
	}

	@Test
	void swaggerUiShortcutRedirectsToTheUi() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/swagger-ui/index.html"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/activation-requests", "/v3/api-docs-private", "/swagger-ui-private"})
	void routesOutsideDocumentationStillRequireAuthentication(@NonNull String path) throws Exception {
		mockMvc.perform(get(path).accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void healthRemainsAccessibleWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

}
