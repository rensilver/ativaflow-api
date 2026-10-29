package com.rensilver.ativaflow_api.mapper;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.model.ActivationRequest;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
@Import(ActivationRequestMapperTests.MapperConfiguration.class)
class ActivationRequestMapperTests {

	@Autowired
	private ActivationRequestMapper mapper;

	@Autowired
	private ObjectMapper objectMapper;

	@ParameterizedTest
	@EnumSource(ActivationStatus.class)
	void mapsOnlyPublicFieldsWithUtcDatesAndPreservesIdentifiers(ActivationStatus status) throws Exception {
		ActivationRequest entity = ActivationRequest.create(" EXT-12345 ", "CUST-10001", "INTERNET-500", "consumer-private");
		// Simula dados carregados pela JPA, incluindo metadados internos que nunca devem chegar à API.
		ReflectionTestUtils.setField(entity, "id", UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90"));
		ReflectionTestUtils.setField(entity, "status", status);
		ReflectionTestUtils.setField(entity, "createdAt", Instant.parse("2026-10-28T15:00:00Z"));
		ReflectionTestUtils.setField(entity, "updatedAt", Instant.parse("2026-10-28T15:01:00Z"));
		ReflectionTestUtils.setField(entity, "version", 4L);
		ReflectionTestUtils.setField(entity, "attemptCount", 2);
		ReflectionTestUtils.setField(entity, "nextAttemptAt", Instant.parse("2026-10-28T15:05:00Z"));
		ReflectionTestUtils.setField(entity, "leaseUntil", Instant.parse("2026-10-28T15:06:00Z"));
		ReflectionTestUtils.setField(entity, "claimedBy", "worker-private");
		ReflectionTestUtils.setField(entity, "lastErrorCode", "PROVIDER_TIMEOUT");
		ReflectionTestUtils.setField(entity, "reconciliationRequired", true);

		var response = mapper.toResponse(entity);

		assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)))
				.isEqualTo(objectMapper.readTree("""
						{
						  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
						  "externalId": " EXT-12345 ",
						  "customerId": "CUST-10001",
						  "productCode": "INTERNET-500",
						  "status": "%s",
						  "createdAt": "2026-10-28T15:00:00Z",
						  "updatedAt": "2026-10-28T15:01:00Z"
						}
						""".formatted(status.name())));
	}

	@Test
	void preservesNullAuditDatesBeforePersistence() {
		ActivationRequest entity = ActivationRequest.create("EXT-1", "CUSTOMER-1", "PRODUCT-1", "consumer-private");

		var response = mapper.toResponse(entity);

		assertThat(response.id()).isEqualTo(entity.getId());
		assertThat(response.status()).isEqualTo(ActivationStatus.PENDING);
		assertThat(response.createdAt()).isNull();
		assertThat(response.updatedAt()).isNull();
	}

	@Test
	void keepsEntityCreationOutOfMappers() {
		var mapperClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.rensilver.ativaflow_api.mapper");

		noClasses().should().callMethod(ActivationRequest.class, "create",
				String.class, String.class, String.class, String.class)
				.because("entity creation requires an owner from the trusted application context")
				.check(mapperClasses);
	}

	@Test
	void returnsNullForNullEntity() {
		assertThat(mapper.toResponse(null)).isNull();
	}

	@TestConfiguration(proxyBeanMethods = false)
	@ComponentScan(basePackageClasses = ActivationRequestMapper.class)
	static class MapperConfiguration {
	}
}
