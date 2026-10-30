package com.rensilver.ativaflow_api.controller;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Optional;

import com.rensilver.ativaflow_api.client.provider.HttpActivationProviderClient;
import com.rensilver.ativaflow_api.exception.ApiExceptionHandler;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.ActivationRequestAttemptService;
import com.rensilver.ativaflow_api.service.ActivationRequestClaimService;
import com.rensilver.ativaflow_api.service.ActivationRequestCreationTransaction;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeHandler;
import com.rensilver.ativaflow_api.service.ActivationRequestOutcomeService;
import com.rensilver.ativaflow_api.service.ActivationRequestService;
import com.rensilver.ativaflow_api.service.model.ClaimedActivationRequest;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.worker.ActivationRequestWorker;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Contrato em memória, com criação, MVC, cliente HTTP e worker reais; sem Spring Boot ou banco. */
class AsyncProviderFailureIsolationUnitTests {

	private static final String PATH = "/api/v1/requests";
	private static final String CONSUMER = "consumer-a";
	private final ActivationRequestRepository repository = mock(ActivationRequestRepository.class);
	private final ActivationRequestMapper mapper = Mappers.getMapper(ActivationRequestMapper.class);
	private ActivationRequest stored;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		var creation = new ActivationRequestCreationTransaction(repository, mapper);
		var service = new ActivationRequestService(repository, mapper, creation);
		mvc = standaloneSetup(new ActivationRequestController(service))
				.setControllerAdvice(new ApiExceptionHandler()).build();
		when(repository.saveAndFlush(any(ActivationRequest.class))).thenAnswer(invocation -> {
			stored = invocation.getArgument(0);
			ReflectionTestUtils.setField(stored, "createdAt", Instant.parse("2026-10-29T12:00:00Z"));
			ReflectionTestUtils.setField(stored, "updatedAt", Instant.parse("2026-10-29T12:00:00Z"));
			ReflectionTestUtils.setField(stored, "version", 0L);
			return stored;
		});
	}

	@ParameterizedTest
	@CsvSource({
			"400, PROVIDER_REJECTED",
			"429, PROVIDER_UNCONFIRMED_RESPONSE",
			"500, PROVIDER_INTERNAL_ERROR_OUTCOME_UNKNOWN",
			"503, PROVIDER_UNCONFIRMED_RESPONSE",
			"0, PROVIDER_TIMEOUT",
			"-1, PROVIDER_COMMUNICATION_FAILURE"
	})
	void providerFailureIsRecordedByWorkerWhileCreationKeepsItsOwnHttpContract(int providerStatus, String errorCode)
			throws Exception {
		// Sem expectativas: qualquer chamada externa durante o POST faz o teste falhar.
		var builder = RestClient.builder().baseUrl("http://provider.example");
		var server = MockRestServiceServer.bindTo(builder).build();
		var provider = new HttpActivationProviderClient(builder.build());

		var created = create("EXT-1")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(header().string("Location", PATH + "/" + stored.getId()))
				.andExpect(header().doesNotExist("Retry-After"))
				.andReturn().getResponse();
		server.verify();

		var claim = new ClaimedActivationRequest(stored.getId(), 1, "worker-a", Instant.parse("2026-10-29T12:00:30Z"));
		var attempt = new PreparedActivationAttempt(stored.getId(), 2, "worker-a", claim.leaseUntil(), 1);
		var claims = mock(ActivationRequestClaimService.class);
		var attempts = mock(ActivationRequestAttemptService.class);
		var outcomes = mock(ActivationRequestOutcomeService.class);
		when(claims.claimNext(anyString())).thenReturn(Optional.of(claim));
		when(attempts.prepareAttempt(claim)).thenReturn(Optional.of(attempt));
		when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
		if (providerStatus == 400) {
			when(outcomes.recordPermanentFailure(attempt, errorCode)).thenReturn(true);
		}
		else {
			when(attempts.recordAmbiguousOutcome(attempt, errorCode)).thenReturn(true);
		}
		var worker = new ActivationRequestWorker(claims, attempts,
				new ActivationRequestOutcomeHandler(attempts, outcomes), provider, repository);

		ResponseActions externalCall = server.expect(requestTo("http://provider.example/provider/v1/activate"))
				.andExpect(method(HttpMethod.POST));
		if (providerStatus == 0) {
			externalCall.andRespond(withException(new SocketTimeoutException("private timeout details")));
		}
		else if (providerStatus == -1) {
			externalCall.andRespond(withException(new java.io.IOException("private connection details")));
		}
		else {
			externalCall.andRespond(withStatus(HttpStatusCode.valueOf(providerStatus))
					.header("Retry-After", "120").header("X-Provider-Secret", "private-header")
					.body("private provider details"));
		}

		assertThat(worker.processNext()).isTrue();
		server.verify();
		if (providerStatus == 400) {
			verify(outcomes).recordPermanentFailure(attempt, errorCode);
		}
		else {
			verify(attempts).recordAmbiguousOutcome(attempt, errorCode);
		}

		// A falha de uma ativação também não impede a criação da próxima solicitação.
		create("EXT-2")
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.externalId").value("EXT-2"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.length()").value(7))
				.andExpect(header().doesNotExist("Retry-After"))
				.andExpect(header().doesNotExist("X-Provider-Secret"));
		assertThat(created.getContentAsString()).doesNotContain("private", "PROVIDER_", "error");
		server.verify();
	}

	@Test
	void repeatingFailedActivationReturnsItsRepresentationWithoutTurningProviderFailureIntoHttpError() throws Exception {
		var failed = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", CONSUMER);
		failed.startProcessing();
		failed.fail("PROVIDER_REJECTED");
		when(repository.findByExternalIdAndConsumerId("EXT-1", CONSUMER)).thenReturn(Optional.of(failed));

		create("EXT-1")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(failed.getId().toString()))
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.length()").value(7))
				.andExpect(jsonPath("$.lastErrorCode").doesNotExist())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(header().doesNotExist("Retry-After"));
		assertThat(stored).isNull();
	}

	@Test
	void persistenceFailureStillReturnsHttpErrorInsteadOfPretendingCreationSucceeded() throws Exception {
		when(repository.saveAndFlush(any(ActivationRequest.class)))
				.thenThrow(new DataAccessResourceFailureException("private database details"));

		create("EXT-1")
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
				.andExpect(header().doesNotExist("Location"));
	}

	@Test
	void creationAndApiErrorHandlingCannotDependOnProviderOrWorker() {
		var production = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.rensilver.ativaflow_api");

		noClasses().that().haveFullyQualifiedName(ActivationRequestController.class.getName())
				.or().haveFullyQualifiedName(ActivationRequestService.class.getName())
				.or().haveFullyQualifiedName(ActivationRequestCreationTransaction.class.getName())
				.or().haveFullyQualifiedName(ApiExceptionHandler.class.getName())
				.should().dependOnClassesThat().resideInAnyPackage(
						"com.rensilver.ativaflow_api.client.provider..", "com.rensilver.ativaflow_api.worker..")
				.because("creation acknowledges local persistence independently of asynchronous provider failures")
				.check(production);
	}

	private ResultActions create(String externalId) throws Exception {
		return mvc.perform(post(PATH).principal(() -> CONSUMER).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"externalId":"%s","customerId":"CUSTOMER-1","productCode":"PRODUCT-1"}
						""".formatted(externalId)));
	}
}
