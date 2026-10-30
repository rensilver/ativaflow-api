package com.rensilver.ativaflow_api.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

public final class LogCapture implements AutoCloseable {

	private final Logger logger;
	private final Level previousLevel;
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
		@Override
		protected void append(ILoggingEvent event) {
			event.prepareForDeferredProcessing();
			super.append(event);
		}
	};

	public LogCapture(Class<?> source) {
		logger = (Logger) LoggerFactory.getLogger(source);
		previousLevel = logger.getLevel();
		logger.setLevel(Level.INFO);
		appender.start();
		logger.addAppender(appender);
	}

	public List<ILoggingEvent> events() {
		return appender.list;
	}

	public static Map<String, Object> fields(ILoggingEvent event) {
		Map<String, Object> fields = new LinkedHashMap<>();
		event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
		return fields;
	}

	@Override
	public void close() {
		logger.detachAppender(appender);
		appender.stop();
		logger.setLevel(previousLevel);
	}
}
