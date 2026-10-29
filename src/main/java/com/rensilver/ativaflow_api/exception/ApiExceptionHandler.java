package com.rensilver.ativaflow_api.exception;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rensilver.ativaflow_api.observability.CorrelationId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Traduz falhas da API e do MVC em respostas públicas seguras e correlacionadas. */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final URI VALIDATION_ERROR_TYPE = URI.create("urn:ativaflow:problem:validation-error");
	// Apenas textos públicos fixos: mensagens interpoladas podem carregar valores e detalhes internos.
	private static final Map<String, String> VALIDATION_MESSAGES = Map.of(
			"NotBlank", "Must not be blank",
			"NotBlankText", "Must not be blank",
			"NotNull", "Required value is missing",
			"Size", "Invalid length",
			"Min", "Value out of range",
			"Max", "Value out of range",
			"Pattern", "Invalid format");

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
				problem.setType(VALIDATION_ERROR_TYPE);
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

		HttpHeaders responseHeaders = safeHeaders(exception, headers);
		responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		responseHeaders.set(CorrelationId.HEADER, correlationId);
		return super.handleExceptionInternal(exception, problem, responseHeaders, status, request);
	}

	private HttpHeaders safeHeaders(Exception exception, HttpHeaders headers) {
		HttpHeaders safe = new HttpHeaders();
		// Os cabeçalhos de negociação são gerados pelo MVC, não por erros arbitrários da integração.
		if (exception instanceof HttpRequestMethodNotSupportedException) {
			copyHeader(headers, safe, HttpHeaders.ALLOW);
		}
		if (exception instanceof HttpMediaTypeNotSupportedException) {
			copyHeader(headers, safe, HttpHeaders.ACCEPT);
			copyHeader(headers, safe, HttpHeaders.ACCEPT_PATCH);
		}
		List<String> retryAfter = headers.get(HttpHeaders.RETRY_AFTER);
		if (retryAfter != null && retryAfter.size() == 1 && isSafeRetryAfter(retryAfter.getFirst())) {
			safe.set(HttpHeaders.RETRY_AFTER, retryAfter.getFirst());
		}
		return safe;
	}

	private void copyHeader(HttpHeaders source, HttpHeaders target, String name) {
		List<String> values = source.get(name);
		if (values != null) {
			target.put(name, List.copyOf(values));
		}
	}

	private boolean isSafeRetryAfter(String value) {
		if (value.length() > 128) {
			return false;
		}
		if (value.matches("[0-9]+")) {
			return true;
		}
		try {
			ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
			return true;
		}
		catch (DateTimeParseException ignored) {
			return false;
		}
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
			case MissingServletRequestParameterException ignored -> "Invalid request parameters";
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
				var pathVariable = parameter.getParameterAnnotation(PathVariable.class);
				String field = requestParam != null && !requestParam.name().isEmpty()
						? requestParam.name() : parameter.getParameterName();
				if (pathVariable != null && !pathVariable.name().isEmpty()) {
					field = pathVariable.name();
				}
				String publicField = field != null ? field : "request";
				return result.getResolvableErrors().stream().map(error ->
						new ValidationError(publicField, safeValidationMessage(error)));
			}).toList();
		}
		if (exception instanceof MethodArgumentTypeMismatchException mismatch) {
			return List.of(new ValidationError(mismatch.getName(), "Invalid value"));
		}
		if (exception instanceof MissingServletRequestParameterException missing) {
			return List.of(new ValidationError(missing.getParameterName(), "Required parameter is missing"));
		}
		return List.of();
	}

	private ValidationError validationError(ObjectError error) {
		return new ValidationError(error instanceof FieldError fieldError ? fieldError.getField() : "request",
				safeValidationMessage(error));
	}

	private String safeValidationMessage(MessageSourceResolvable error) {
		String[] codes = error.getCodes();
		if (codes != null) {
			for (String code : codes) {
				String message = VALIDATION_MESSAGES.get(code);
				if (message != null) {
					return message;
				}
			}
		}
		return "Invalid value";
	}

	private record ValidationError(String field, String message) {
	}
}
