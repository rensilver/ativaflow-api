package com.rensilver.ativaflow_api.exception;

/** Página, tamanho ou deslocamento fora dos limites suportados pela listagem. */
public class InvalidPaginationException extends RuntimeException {

	public InvalidPaginationException() {
		super("Pagination parameters exceed supported range");
	}
}
