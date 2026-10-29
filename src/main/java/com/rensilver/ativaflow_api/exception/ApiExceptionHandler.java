package com.rensilver.ativaflow_api.exception;

import java.util.List;
import java.util.UUID;

import com.rensilver.ativaflow_api.observability.CorrelationId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Traduz falhas da API e do MVC em respostas públicas seguras e correlacionadas. */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ActivationRequestConflictException.class)
	public ResponseEntity<Object> handleConflict(ActivationRequestConflictException exception, WebRequest request) {
		return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.CONFLICT, request);
	}

	@ExceptionHandler(InvalidPaginationException.class)
	public ResponseEntity<Object> handleInvalidRequestParameters(InvalidPaginationException exception, WebRequest request) {
		return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
	}

	@ExceptionHandler(ActivationRequestNotFoundException.class)
	public ResponseEntity<Object> handleNotFound(ActivationRequestNotFoundException exception, WebRequest request) {
		return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) throws Exception {
		// A cadeia de segurança é responsável pela semântica e pelos headers de autenticação/autorização.
		if (exception instanceof AuthenticationException || exception instanceof AccessDeniedException) {
			throw exception;
		}
		return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	@Override
	@Nullable
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String correlationId = CorrelationId.current();
		if (correlationId == null) {
			correlationId = UUID.randomUUID().toString();
		}
		ProblemDetail problem = ApiProblemDetails.create(status, safeDetail(exception, status),
				((ServletWebRequest) request).getRequest().getRequestURI(), correlationId);
		if (status.value() == HttpStatus.BAD_REQUEST.value()) {
			List<ValidationError> errors = validationErrors(exception);
			if (!errors.isEmpty()) {
				problem.setProperty("errors", errors);
			}
		}

		if (status.is5xxServerError()) {
			log.atError().addKeyValue("correlationId", correlationId).addKeyValue("status", status.value())
					.setCause(exception).log("api_request_failed");
		}
		else {
			log.atWarn().addKeyValue("correlationId", correlationId).addKeyValue("status", status.value())
					.log("api_request_rejected");
		}

		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.putAll(headers);
		responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		responseHeaders.set(CorrelationId.HEADER, correlationId);
		return super.handleExceptionInternal(exception, problem, responseHeaders, status, request);
	}

	private String safeDetail(Exception exception, HttpStatusCode status) {
		if (status.is5xxServerError()) {
			return "An unexpected error occurred";
		}
		return switch (exception) {
			case ActivationRequestConflictException ignored -> "Activation request conflict";
			case ActivationRequestNotFoundException ignored -> "Activation request not found";
			case HttpMessageNotReadableException ignored -> "Invalid request body";
			case MethodArgumentNotValidException ignored -> "Request validation failed";
			case HandlerMethodValidationException ignored -> "Invalid request parameters";
			case TypeMismatchException ignored -> "Invalid request parameters";
			case InvalidPaginationException ignored -> "Invalid request parameters";
			default -> ApiProblemDetails.title(status);
		};
	}

	private List<ValidationError> validationErrors(Exception exception) {
		if (exception instanceof MethodArgumentNotValidException validation) {
			return validation.getBindingResult().getAllErrors().stream().map(this::validationError).toList();
		}
		if (exception instanceof HandlerMethodValidationException validation) {
			return validation.getParameterValidationResults().stream().flatMap(result -> {
				if (result instanceof ParameterErrors errors) {
					return errors.getAllErrors().stream().map(this::validationError);
				}
				var parameter = result.getMethodParameter();
				var requestParam = parameter.getParameterAnnotation(RequestParam.class);
				String field = requestParam != null && !requestParam.name().isEmpty()
						? requestParam.name() : parameter.getParameterName();
				return result.getResolvableErrors().stream().map(error ->
						new ValidationError(field != null ? field : "request", safeValidationMessage(error.getDefaultMessage())));
			}).toList();
		}
		return List.of();
	}

	private ValidationError validationError(ObjectError error) {
		return new ValidationError(error instanceof FieldError fieldError ? fieldError.getField() : "request",
				safeValidationMessage(error.getDefaultMessage()));
	}

	private String safeValidationMessage(@Nullable String message) {
		return message != null ? message : "Invalid value";
	}

	private record ValidationError(String field, String message) {
	}
}
