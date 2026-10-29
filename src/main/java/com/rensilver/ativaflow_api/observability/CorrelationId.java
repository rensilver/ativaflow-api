package com.rensilver.ativaflow_api.observability;

import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.lang.Nullable;

/** Metadado técnico limitado a caracteres seguros para headers e logs. */
public final class CorrelationId {

	public static final String HEADER = "X-Correlation-ID";
	public static final String MDC_KEY = "correlationId";
	private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

	private CorrelationId() {
	}

	public static boolean isValid(@Nullable String value) {
		return value != null && SAFE_VALUE.matcher(value).matches();
	}

	@Nullable
	public static String current() {
		String value = MDC.get(MDC_KEY);
		return isValid(value) ? value : null;
	}

	/** Restaura o contexto anterior, inclusive em caso de exceção. */
	public static Scope withId(String value) {
		return new Scope(value);
	}

	public static final class Scope implements AutoCloseable {

		@Nullable
		private final String previous = MDC.get(MDC_KEY);

		private Scope(String value) {
			MDC.put(MDC_KEY, value);
		}

		@Override
		public void close() {
			if (previous == null) {
				MDC.remove(MDC_KEY);
			}
			else {
				MDC.put(MDC_KEY, previous);
			}
		}
	}
}
