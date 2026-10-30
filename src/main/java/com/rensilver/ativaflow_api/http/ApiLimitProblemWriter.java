package com.rensilver.ativaflow_api.http;

import java.io.IOException;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rensilver.ativaflow_api.exception.ApiProblemDetails;
import com.rensilver.ativaflow_api.observability.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@Slf4j
@RequiredArgsConstructor
final class ApiLimitProblemWriter {

	private final ObjectMapper mapper;

	void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
			throws IOException {
		String correlationId = CorrelationId.current();
		if (correlationId == null) correlationId = UUID.randomUUID().toString();
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setHeader(CorrelationId.HEADER, correlationId);
		log.atWarn().addKeyValue("correlationId", correlationId).addKeyValue("status", status.value())
				.log("api_request_rejected");
		mapper.writeValue(response.getOutputStream(),
				ApiProblemDetails.create(status, detail, request.getRequestURI(), correlationId));
	}
}
