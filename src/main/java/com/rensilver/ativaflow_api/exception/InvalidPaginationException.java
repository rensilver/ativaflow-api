package com.rensilver.ativaflow_api.exception;

/** A página solicitada excede o intervalo de deslocamentos suportado pela persistência. */
public class InvalidPaginationException extends RuntimeException {

	public InvalidPaginationException() {
		super("Pagination offset exceeds supported range");
	}
}
