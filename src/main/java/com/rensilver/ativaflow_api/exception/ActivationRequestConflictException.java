package com.rensilver.ativaflow_api.exception;

/** Mensagem genérica para divergências de conteúdo ou de proprietário. */
public class ActivationRequestConflictException extends RuntimeException {

	public ActivationRequestConflictException() {
		super("Activation request conflict");
	}
}
