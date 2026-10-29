package com.rensilver.ativaflow_api.service.model;

import com.rensilver.ativaflow_api.dto.response.ActivationRequestResponse;

/** Distingue uma nova criação da reutilização de uma solicitação existente. */
public record CreateActivationRequestResult(ActivationRequestResponse response, boolean created) {
}
