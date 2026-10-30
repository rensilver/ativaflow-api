package com.rensilver.ativaflow_api.service.model;

import java.time.Instant;
import java.util.UUID;

/** Fotografia imutável da posse adquirida, usada na preparação condicional; não autoriza HTTP sozinha. */
public record ClaimedActivationRequest(UUID id, long version, String claimedBy, Instant leaseUntil) {
}
