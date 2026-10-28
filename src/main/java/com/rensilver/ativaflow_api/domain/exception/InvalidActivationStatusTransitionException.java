package com.rensilver.ativaflow_api.domain.exception;

import com.rensilver.ativaflow_api.domain.ActivationStatus;

public class InvalidActivationStatusTransitionException extends RuntimeException {

	public InvalidActivationStatusTransitionException(
            ActivationStatus current,
            ActivationStatus target) {

		super("Cannot transition activation request from %s to %s"
				.formatted(current, target));
	}
}
