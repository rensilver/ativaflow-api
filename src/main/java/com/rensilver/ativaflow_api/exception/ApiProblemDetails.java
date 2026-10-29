package com.rensilver.ativaflow_api.exception;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Contrato RFC 9457 compartilhado pelo MVC e pelos filtros de segurança. */
public final class ApiProblemDetails {

	private static final URI TYPE = URI.create("about:blank");

	private ApiProblemDetails() {
	}

	public static ProblemDetail create(HttpStatusCode status, String detail, String path, String correlationId) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(TYPE);
		problem.setTitle(title(status));
		problem.setInstance(URI.create(path));
		problem.setProperty("correlationId", correlationId);
		return problem;
	}

	public static String title(HttpStatusCode status) {
		HttpStatus knownStatus = HttpStatus.resolve(status.value());
		return knownStatus != null ? knownStatus.getReasonPhrase() : "Request failed";
	}
}
