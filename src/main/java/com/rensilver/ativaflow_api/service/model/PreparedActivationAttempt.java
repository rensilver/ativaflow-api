package com.rensilver.ativaflow_api.service.model;

import java.time.Instant;
import java.util.UUID;

/** Tentativa registrada antes do HTTP; a versão resultante deve proteger a futura escrita de resultado. */
public record PreparedActivationAttempt(UUID id, long version, String claimedBy, Instant leaseUntil, int attemptCount) {
}
