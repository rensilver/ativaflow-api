package com.rensilver.ativaflow_api.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rensilver.ativaflow_api.domain.ActivationStatus;
import com.rensilver.ativaflow_api.dto.request.CreateActivationRequest;
import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ActivationDtosJsonTests {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void readsAndWritesOnlyCreationFieldsWithoutNormalizingIdentifiers() throws Exception {
		String json = """
				{
				  "externalId": " EXT-12345 ",
				  "customerId": "CUST-10001",
				  "productCode": "INTERNET-500"
				}
				""";

		CreateActivationRequest request = objectMapper.readValue(json, CreateActivationRequest.class);

		assertThat(request.externalId()).isEqualTo(" EXT-12345 ");
		assertThat(request.customerId()).isEqualTo("CUST-10001");
		assertThat(request.productCode()).isEqualTo("INTERNET-500");
		assertThat(objectMapper.readTree(objectMapper.writeValueAsString(request)))
				.isEqualTo(objectMapper.readTree(json));
	}

	@ParameterizedTest
	@EnumSource(ActivationStatus.class)
	void writesOnlyPublicResponseFieldsWithUtcTimestamps(ActivationStatus status) throws Exception {
		var response = new ActivationRequestResponse(
				UUID.fromString("0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90"),
				"EXT-12345", "CUST-10001", "INTERNET-500", status,
				Instant.parse("2026-10-26T14:03:11Z"), Instant.parse("2026-10-26T14:04:12Z"));

		assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)))
				.isEqualTo(objectMapper.readTree("""
						{
						  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
						  "externalId": "EXT-12345",
						  "customerId": "CUST-10001",
						  "productCode": "INTERNET-500",
						  "status": "%s",
						  "createdAt": "2026-10-26T14:03:11Z",
						  "updatedAt": "2026-10-26T14:04:12Z"
						}
						""".formatted(status.name())));
	}
}
