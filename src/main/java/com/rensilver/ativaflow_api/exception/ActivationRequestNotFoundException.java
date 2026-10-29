package com.rensilver.ativaflow_api.exception;

/** Usada tanto para IDs inexistentes quanto para solicitações de outro consumidor. */
public class ActivationRequestNotFoundException extends RuntimeException {

	public ActivationRequestNotFoundException() {
		super("Activation request not found");
	}
}
