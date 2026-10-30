package com.rensilver.ativaflow_api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.rensilver.ativaflow_api.config.OpenApiConfiguration;
import com.rensilver.ativaflow_api.controller.ActivationRequestController;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestPageResponse;
import com.rensilver.ativaflow_api.http.HttpLimitConfiguration;
import com.rensilver.ativaflow_api.security.LocalJwtDecoderConfiguration;
import com.rensilver.ativaflow_api.security.SecurityConfiguration;
import com.rensilver.ativaflow_api.security.SecurityProblemHandler;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OpenAPI, recursos Swagger e segurança reais no perfil local, sem banco ou rede. */
@SpringBootTest(classes = SwaggerSecurityIntegrationTests.WebApplication.class,
		properties = "management.endpoint.health.group.readiness.include=readinessState")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("local")
class SwaggerSecurityIntegrationTests {

	@TempDir
	static Path temporary;
	private static Path material;

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private ActivationRequestService service;

	@DynamicPropertySource
	static void jwtProperties(DynamicPropertyRegistry registry) throws Exception {
		material = Path.of(runJwtTool("create").strip());
		runJwtTool("monitor-token", material.toString());
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://ativaflow.local");
		registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "ativaflow-api");
		registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
				() -> material.resolve("public.pem").toUri().toString());
	}

	@Test
	void openApiProvidesHttpBearerJwtSchemeForSwaggerAuthorization() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("AtivaFlow API"))
				.andExpect(jsonPath("$.info.version").value("v1"))
				.andExpect(jsonPath("$.components.securitySchemes.length()").value(1))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
	}

	@ParameterizedTest
	@CsvSource({"/api/v1/requests, post", "/api/v1/requests, get", "/api/v1/requests/{id}, get"})
	void protectedOperationsReferenceTheBearerScheme(String path, String method) throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['" + path + "']." + method + ".security.length()").value(1))
				.andExpect(jsonPath("$.paths['" + path + "']." + method + ".security[0].bearerAuth").isEmpty())
				// A exigência fica nas operações protegidas, sem impor autenticação global.
				.andExpect(jsonPath("$.security").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/swagger-ui/index.html", "/swagger-ui/swagger-ui.css",
			"/swagger-ui/swagger-ui-bundle.js", "/swagger-ui/swagger-initializer.js",
			"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config"})
	void documentationResourcesArePublicLocally(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isOk());
	}

	@Test
	void swaggerUiLoadsTheDocumentContainingTheBearerScheme() throws Exception {
		mvc.perform(get("/swagger-ui.html"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/swagger-ui/index.html"));
		mvc.perform(get("/v3/api-docs/swagger-config"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value("/v3/api-docs"));
	}

	@ParameterizedTest
	@CsvSource({"POST, /api/v1/requests", "GET, /api/v1/requests",
			"GET, /api/v1/requests/00000000-0000-0000-0000-000000000001"})
	void businessEndpointsStillRequireAuthentication(String method, String path) throws Exception {
		mvc.perform(request(HttpMethod.valueOf(method), path))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	@ParameterizedTest
	@CsvSource({"POST, /api/v1/requests", "GET, /api/v1/requests",
			"GET, /api/v1/requests/00000000-0000-0000-0000-000000000001"})
	void businessEndpointsRejectInvalidBearerTokens(String method, String path) throws Exception {
		mvc.perform(request(HttpMethod.valueOf(method), path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
				.andExpect(status().isUnauthorized());
	}

	@ParameterizedTest
	@CsvSource({"POST, /api/v1/requests", "GET, /api/v1/requests",
			"GET, /api/v1/requests/00000000-0000-0000-0000-000000000001"})
	void validLocalTokenWithoutBusinessScopesIsForbidden(String method, String path) throws Exception {
		mvc.perform(request(HttpMethod.valueOf(method), path)
				.header(HttpHeaders.AUTHORIZATION, bearer("monitoring.header")))
				.andExpect(status().isForbidden());
	}

	@Test
	void jwtGeneratedByExistingToolAuthenticatesSwaggerStyleRequest() throws Exception {
		when(service.listActivationRequests(null, null, 0, 20, "demo-consumer"))
				.thenReturn(new ActivationRequestPageResponse(List.of(), 0, 20, 0, 0));

		mvc.perform(get("/api/v1/requests").header(HttpHeaders.AUTHORIZATION, bearer("authorization.header")))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	private static String bearer(String file) throws Exception {
		return Files.readString(material.resolve(file)).strip().substring("Authorization: ".length());
	}

	private static String runJwtTool(String... arguments) throws Exception {
		var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
				"-Djava.io.tmpdir=" + temporary, Path.of("scripts/LocalJwt.java").toAbsolutePath().toString()));
		command.addAll(List.of(arguments));
		var process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as(output).isZero();
		return output;
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
			FlywayAutoConfiguration.class})
	@Import({OpenApiConfiguration.class, ActivationRequestController.class, SecurityConfiguration.class,
			SecurityProblemHandler.class, LocalJwtDecoderConfiguration.class, HttpLimitConfiguration.class})
	static class WebApplication {
	}
}
