package com.rensilver.ativaflow_api.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.rensilver.ativaflow_api.controller.ActivationRequestController;
import com.rensilver.ativaflow_api.exception.ApiExceptionHandler;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestCreationTransaction;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cadeia JWT, controller e serviços reais em memória; somente o banco é substituído. */
class JwtConsumerOwnershipUnitTests {

	private static final String PATH = "/api/v1/requests";
	private static final String ISSUER = "https://issuer.example.test";
	private static final String BODY = """
			{"externalId":"EXT-1","customerId":"CUSTOMER-1","productCode":"PRODUCT-1"}
			""";
	private static final KeyPair KEYS = keys();
	private final ObjectMapper json = new MappingJackson2HttpMessageConverter().getObjectMapper()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	private ActivationRequestRepository repository;
	private StaticWebApplicationContext context;
	private MockMvc mvc;

	@BeforeEach
	void setUp() throws Exception {
		repository = mock(ActivationRequestRepository.class);
		var mapper = Mappers.getMapper(ActivationRequestMapper.class);
		var service = new ActivationRequestService(repository, mapper,
				new ActivationRequestCreationTransaction(repository, mapper));
		context = new StaticWebApplicationContext();
		var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		context.getBeanFactory().registerSingleton("jwtDecoder", decoder);
		context.getBeanFactory().registerSingleton("corsConfigurationSource", new UrlBasedCorsConfigurationSource());
		context.getBeanFactory().registerSingleton("pathMappedEndpoints", new PathMappedEndpoints("/actuator", List.of()));
		var processor = new ObjectPostProcessor<Object>() {
			@Override
			public <O> O postProcess(O object) {
				context.getAutowireCapableBeanFactory().initializeBean(object, object.getClass().getName());
				return object;
			}
		};
		var shared = new HashMap<Class<?>, Object>();
		shared.put(ApplicationContext.class, context);
		var http = new HttpSecurity(processor, new AuthenticationManagerBuilder(processor), shared);
		http.csrf(Customizer.withDefaults()).sessionManagement(Customizer.withDefaults())
				.securityContext(Customizer.withDefaults()).requestCache(Customizer.withDefaults())
				.anonymous(Customizer.withDefaults()).exceptionHandling(Customizer.withDefaults())
				.logout(Customizer.withDefaults()).servletApi(Customizer.withDefaults());
		var filters = new FilterChainProxy(new SecurityConfiguration()
				.securityFilterChain(http, new SecurityProblemHandler(json)));
		mvc = MockMvcBuilders.standaloneSetup(new ActivationRequestController(service))
				.setControllerAdvice(new ApiExceptionHandler())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(json))
				.addFilters(new CorrelationIdFilter(), filters).build();
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		context.close();
	}

	@ParameterizedTest(name = "{0}: {1}")
	@MethodSource("invalidIdentities")
	void rejectsInvalidIdentityBeforeAccessingTheDatabase(String operation, String description, Object subject)
			throws Exception {
		MockHttpServletRequestBuilder request = switch (operation) {
			case "create" -> post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY);
			case "get" -> get(PATH + "/00000000-0000-0000-0000-000000000001");
			default -> get(PATH);
		};

		mvc.perform(authenticate(request, subject, "requests/read requests/write"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Authentication is required"))
				.andExpect(jsonPath("$.correlationId").value("jwt-owner-test"));
		verifyNoInteractions(repository);
	}

	static Stream<Arguments> invalidIdentities() {
		Map<String, Object> invalid = new java.util.LinkedHashMap<>();
		invalid.put("missing sub despite other identity claims", null);
		invalid.put("empty sub", "");
		invalid.put("blank sub", " \t\r\n");
		invalid.put("unicode whitespace sub", "\u2003");
		invalid.put("sub over storage limit", "s".repeat(101));
		invalid.put("numeric sub", 123);
		invalid.put("array sub", List.of("consumer-one"));
		invalid.put("object sub", Map.of("id", "consumer-one"));
		return Stream.of("create", "get", "list").flatMap(operation -> invalid.entrySet().stream()
				.map(entry -> Arguments.of(operation, entry.getKey(), entry.getValue())));
	}

	@ParameterizedTest
	@ValueSource(strings = {"consumer-one", " Consumer-A ", "a", "123"})
	void persistsTheExactJwtSubjectAsOwner(String subject) throws Exception {
		when(repository.saveAndFlush(any(ActivationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

		mvc.perform(authenticate(post(PATH).queryParam("consumerId", "query-owner")
				.header("X-Consumer-ID", "header-owner").contentType(MediaType.APPLICATION_JSON).content(BODY),
				subject, "requests/write"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.externalId").value("EXT-1"))
				.andExpect(jsonPath("$.customerId").value("CUSTOMER-1"))
				.andExpect(jsonPath("$.consumerId").doesNotExist());

		var saved = ArgumentCaptor.forClass(ActivationRequest.class);
		verify(repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getConsumerId()).isEqualTo(subject);
		verify(repository).findByExternalIdAndConsumerId("EXT-1", subject);
	}

	@Test
	void acceptsSubjectAtTheStorageLimit() throws Exception {
		String subject = "s".repeat(100);
		when(repository.saveAndFlush(any(ActivationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

		mvc.perform(authenticate(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY),
				subject, "requests/write")).andExpect(status().isCreated());

		var saved = ArgumentCaptor.forClass(ActivationRequest.class);
		verify(repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getConsumerId()).isEqualTo(subject);
	}

	@ParameterizedTest
	@ValueSource(strings = {"consumerId", "consumer_id", "owner", "sub", "client_id"})
	void rejectsOwnershipFieldsInTheBody(String field) throws Exception {
		var body = json.readTree(BODY);
		((com.fasterxml.jackson.databind.node.ObjectNode) body).put(field, "body-owner");

		mvc.perform(authenticate(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body.toString()),
				"consumer-one", "requests/write"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Invalid request body"));
		verifyNoInteractions(repository);
	}

	@Test
	void scopesReadsAndListingToTheSameJwtIdentity() throws Exception {
		var entity = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-one");
		UUID id = entity.getId();
		when(repository.findByIdAndConsumerId(id, "consumer-one")).thenReturn(Optional.of(entity));
		when(repository.findAllByFilters(eq("consumer-two"), isNull(), isNull(), any(Pageable.class)))
				.thenReturn(Page.empty());

		mvc.perform(authenticate(get(PATH + "/" + id), "consumer-one", "requests/read"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
		mvc.perform(authenticate(get(PATH + "/" + id), "consumer-two", "requests/read"))
				.andExpect(status().isNotFound());
		mvc.perform(authenticate(get(PATH).queryParam("consumerId", "consumer-one"),
				"consumer-two", "requests/read"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
		verify(repository).findByIdAndConsumerId(id, "consumer-two");
		verify(repository).findAllByFilters(eq("consumer-two"), isNull(), isNull(), any(Pageable.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "requests/read"})
	void validIdentityStillRequiresWriteScope(String scope) throws Exception {
		mvc.perform(authenticate(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY), "consumer-one", scope))
				.andExpect(status().isForbidden());
		verifyNoInteractions(repository);
	}

	private MockHttpServletRequestBuilder authenticate(MockHttpServletRequestBuilder request, Object subject, String scope)
			throws Exception {
		var claims = new HashMap<String, Object>();
		claims.put("iss", ISSUER);
		claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
		claims.put("scope", scope);
		// Claims alternativas não podem se tornar fallback ou substituir o sub contratado.
		claims.put("client_id", "different-client");
		claims.put("consumerId", "different-consumer");
		claims.put("owner", "different-owner");
		if (subject != null) {
			claims.put("sub", subject);
		}
		var token = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(json.writeValueAsString(claims)));
		token.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.serialize())
				.header("X-Correlation-ID", "jwt-owner-test");
	}

	private static KeyPair keys() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
