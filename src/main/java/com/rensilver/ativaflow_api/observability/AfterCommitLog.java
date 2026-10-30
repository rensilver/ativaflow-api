package com.rensilver.ativaflow_api.observability;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.springframework.lang.Nullable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Emite somente fatos confirmados, usando uma cópia dos campos e restaurando o MDC do chamador. */
public final class AfterCommitLog {

	private AfterCommitLog() {
	}

	public static String correlationId(UUID requestId, @Nullable String persistedId) {
		return CorrelationId.isValid(persistedId) ? persistedId : requestId.toString();
	}

	public static void info(Logger logger, String event, Map<String, Object> fields) {
		var snapshot = Map.copyOf(fields);
		Runnable emit = () -> {
			try (var ignored = CorrelationId.withId((String) snapshot.get(CorrelationId.MDC_KEY))) {
				var builder = logger.atInfo().addKeyValue("event", event);
				snapshot.forEach(builder::addKeyValue);
				builder.log("Activation request lifecycle event");
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					emit.run();
				}
			});
		}
		else {
			emit.run();
		}
	}
}
