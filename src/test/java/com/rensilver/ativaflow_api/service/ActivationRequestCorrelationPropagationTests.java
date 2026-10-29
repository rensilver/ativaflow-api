package com.rensilver.ativaflow_api.service;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.rensilver.ativaflow_api.client.provider.ActivationProviderClient;
import com.rensilver.ativaflow_api.client.provider.HttpActivationProviderClient;
import com.rensilver.ativaflow_api.controller.ActivationRequestController;
import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.mapper.ActivationRequestMapper;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.rensilver.ativaflow_api.observability.CorrelationIdFilter;
import com.rensilver.ativaflow_api.repository.ActivationRequestRepository;
import com.rensilver.ativaflow_api.service.model.ClaimedActivationRequest;
import com.rensilver.ativaflow_api.service.model.PreparedActivationAttempt;
import com.rensilver.ativaflow_api.worker.ActivationRequestWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Componentes reais do fluxo; somente banco e transporte externo são substituídos. */
@ExtendWith(MockitoExtension.class)
class ActivationRequestCorrelationPropagationTests {

	private static final CreateActivationRequest REQUEST =
			new CreateActivationRequest("EXT-1", "CUSTOMER-1", "PRODUCT-1");
	@Mock
	private ActivationRequestRepository repository;
	@Mock
	private ActivationRequestClaimService claims;
	@Mock
	private ActivationRequestAttemptService attempts;
	@Mock
	private ActivationRequestOutcomeHandler outcomes;

	@AfterEach
	void clearContext() {
		MDC.clear();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"original-http-request", "invalid correlation"})
	void carriesReceivedOrGeneratedCorrelationFromPostToLaterWorkerAttempts(String incomingId) throws Exception {
		var persisted = new AtomicReference<ActivationRequest>();
		when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
			persisted.set(invocation.getArgument(0));
			return persisted.get();
		});
		var mvc = MockMvcBuilders.standaloneSetup(new ActivationRequestController(service()))
				.addFilters(new CorrelationIdFilter()).build();
		var post = post("/api/v1/requests").principal(() -> "consumer-a")
				.header(HttpHeaders.AUTHORIZATION, "Bearer consumer.jwt.signature")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"externalId":"EXT-1","customerId":"CUSTOMER-1","productCode":"PRODUCT-1"}
						""");
		if (incomingId != null) {
			post.header("X-Correlation-ID", incomingId);
		}
		var response = mvc.perform(post).andExpect(status().isCreated()).andReturn().getResponse();
		String correlationId = response.getHeader("X-Correlation-ID");
		assertThat(correlationId).isNotBlank();
		assertThat(MDC.get("correlationId")).isNull();

		var builder = RestClient.builder().baseUrl("http://provider.example");
		var server = MockRestServiceServer.bindTo(builder).build();
		for (int attempt = 0; attempt < 2; attempt++) {
			expectActivation(server, correlationId);
		}
		var worker = worker(persisted.get(), new HttpActivationProviderClient(builder.build()));
		assertThat(worker.processNext()).isTrue();
		assertThat(MDC.get("correlationId")).isNull();
		MDC.put("correlationId", "unrelated-worker-context");
		assertThat(worker.processNext()).isTrue();
		assertThat(MDC.get("correlationId")).isEqualTo("unrelated-worker-context");
		server.verify();
	}

	@Test
	void duplicatePostKeepsTheCorrelationOfTheOriginalActivation() {
		var persisted = new AtomicReference<ActivationRequest>();
		when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
			persisted.set(invocation.getArgument(0));
			return persisted.get();
		});
		var service = service();
		MDC.put("correlationId", "original-request");
		var created = service.createActivationRequest(REQUEST, "consumer-a");
		when(repository.findByExternalIdAndConsumerId("EXT-1", "consumer-a"))
				.thenReturn(Optional.of(persisted.get()));
		MDC.put("correlationId", "duplicate-request");
		var duplicate = service.createActivationRequest(REQUEST, "consumer-a");
		assertThat(duplicate.created()).isFalse();
		assertThat(duplicate.response().id()).isEqualTo(created.response().id());
		MDC.clear();

		var builder = RestClient.builder().baseUrl("http://provider.example");
		var server = MockRestServiceServer.bindTo(builder).build();
		expectActivation(server, "original-request");
		assertThat(worker(persisted.get(), new HttpActivationProviderClient(builder.build())).processNext()).isTrue();
		server.verify();
	}

	@Test
	void legacyRequestUsesItsStableIdInsteadOfAnUnrelatedWorkerCorrelation() {
		var legacy = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-a");
		MDC.put("correlationId", "unrelated-worker-context");
		var builder = RestClient.builder().baseUrl("http://provider.example");
		var server = MockRestServiceServer.bindTo(builder).build();
		expectActivation(server, legacy.getId().toString());

		assertThat(worker(legacy, new HttpActivationProviderClient(builder.build())).processNext()).isTrue();

		assertThat(MDC.get("correlationId")).isEqualTo("unrelated-worker-context");
		server.verify();
	}

	@Test
	void workerRestoresPreviousMdcEvenWhenTheProviderThrows() {
		var legacy = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-a");
		var provider = mock(ActivationProviderClient.class);
		var failure = new IllegalStateException("unexpected provider failure");
		doAnswer(invocation -> {
			assertThat(MDC.get("correlationId")).isEqualTo(legacy.getId().toString());
			throw failure;
		}).when(provider).activate(any());
		MDC.put("correlationId", "outer-context");
		MDC.put("unrelated", "preserved");
		var worker = worker(legacy, provider);

		assertThatThrownBy(worker::processNext).isSameAs(failure);

		assertThat(MDC.get("correlationId")).isEqualTo("outer-context");
		assertThat(MDC.get("unrelated")).isEqualTo("preserved");
	}

	private ActivationRequestService service() {
		var mapper = Mappers.getMapper(ActivationRequestMapper.class);
		return new ActivationRequestService(repository, mapper,
				new ActivationRequestCreationTransaction(repository, mapper));
	}

	private ActivationRequestWorker worker(ActivationRequest request, ActivationProviderClient provider) {
		var claim = new ClaimedActivationRequest(request.getId(), 1, "worker-a", Instant.parse("2026-10-29T12:00:30Z"));
		var prepared = new PreparedActivationAttempt(request.getId(), 2, "worker-a", claim.leaseUntil(), 1);
		when(claims.claimNext(anyString())).thenReturn(Optional.of(claim));
		when(attempts.prepareAttempt(claim)).thenReturn(Optional.of(prepared));
		when(repository.findById(request.getId())).thenReturn(Optional.of(request));
		return new ActivationRequestWorker(claims, attempts, outcomes, provider, repository);
	}

	private void expectActivation(MockRestServiceServer server, String correlationId) {
		server.expect(requestTo("http://provider.example/provider/v1/activate"))
				.andExpect(header("X-Correlation-ID", correlationId))
				.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(content().json("""
						{"externalId":"EXT-1","customerId":"CUSTOMER-1","productCode":"PRODUCT-1"}
						""", JsonCompareMode.STRICT))
				.andRespond(withNoContent());
	}
}
