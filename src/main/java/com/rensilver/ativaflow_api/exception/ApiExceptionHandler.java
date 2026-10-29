package com.rensilver.ativaflow_api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ActivationRequestConflictException.class)
	public ProblemDetail handleConflict(ActivationRequestConflictException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
	}

	@ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
			InvalidPaginationException.class})
	public ProblemDetail handleInvalidRequestParameters() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request parameters");
	}

	@ExceptionHandler(ActivationRequestNotFoundException.class)
	public ProblemDetail handleNotFound(ActivationRequestNotFoundException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
	}
}
