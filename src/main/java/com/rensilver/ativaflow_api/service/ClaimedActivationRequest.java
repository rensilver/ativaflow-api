package com.rensilver.ativaflow_api.service;

import java.time.Instant;
import java.util.UUID;

/** Fotografia imutável da posse adquirida, para as futuras operações condicionais do worker. */
public record ClaimedActivationRequest(UUID id, long version, String claimedBy, Instant leaseUntil) {
}
