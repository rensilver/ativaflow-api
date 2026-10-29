package com.rensilver.ativaflow_api.security;

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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Respostas seguras para falhas anteriores ao MVC, preservando os desafios Bearer. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ObjectMapper mapper;

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException {
		// Mantém código/status OAuth sem expor detalhes do decoder no cabeçalho.
		AuthenticationException safeException = exception;
		if (exception instanceof OAuth2AuthenticationException oauthException) {
			var error = oauthException.getError();
			var status = error instanceof BearerTokenError bearerError
					? bearerError.getHttpStatus() : HttpStatus.UNAUTHORIZED;
			safeException = new OAuth2AuthenticationException(
					new BearerTokenError(error.getErrorCode(), status, null, null));
		}
		new BearerTokenAuthenticationEntryPoint().commence(request, response, safeException);
		var status = HttpStatus.valueOf(response.getStatus());
		writeProblem(request, response, status,
				status == HttpStatus.BAD_REQUEST ? "Invalid authentication request" : "Authentication is required");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
			throws IOException {
		new BearerTokenAccessDeniedHandler().handle(request, response, exception);
		writeProblem(request, response, HttpStatus.FORBIDDEN, "Access is denied");
	}

	private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
			throws IOException {
		String correlationId = CorrelationId.current();
		if (correlationId == null) {
			correlationId = UUID.randomUUID().toString();
		}
		var problem = ApiProblemDetails.create(status, detail, request.getRequestURI(), correlationId);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setHeader(CorrelationId.HEADER, correlationId);
		log.atWarn().addKeyValue("correlationId", correlationId).addKeyValue("status", status.value())
				.log("api_request_rejected");
		mapper.writeValue(response.getOutputStream(), problem);
	}
}
