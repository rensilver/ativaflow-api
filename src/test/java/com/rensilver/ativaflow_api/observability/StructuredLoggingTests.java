package com.rensilver.ativaflow_api.observability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredLoggingTests {

	@TempDir Path directory;

	@Test
	void productionConsoleEmitsJsonLinesWithTypedTechnicalFieldsAndEscapesMessages() throws Exception {
		String output = runProbe("prod");
		var mapper = new ObjectMapper();
		JsonNode probe = null;
		for (String line : output.lines().filter(value -> !value.isBlank()).toList()) {
			assertThat(line).as("each production console line is JSON").startsWith("{");
			var json = mapper.readTree(line);
			assertThat(json.isObject()).isTrue();
			assertThat(OffsetDateTime.parse(json.path("@timestamp").asText())).isNotNull();
			if (json.path("event").asText().equals("logging_probe")) {
				probe = json;
			}
		}
		assertThat(probe).isNotNull();
		assertThat(probe.path("level").asText()).isEqualTo("INFO");
		assertThat(probe.path("logger_name").asText()).isEqualTo(Probe.class.getName());
		assertThat(probe.path("thread_name").asText()).isNotBlank();
		assertThat(probe.path("message").asText()).isEqualTo("Message with\na newline and \"quotes\"");
		assertThat(probe.path("correlationId").asText()).isEqualTo("json-probe-1");
		assertThat(probe.path("requestId").asText()).isEqualTo("ef9147a7-f092-46cb-ab55-d2f5dfb86bf0");
		assertThat(probe.path("attempt").isIntegralNumber()).isTrue();
		assertThat(probe.path("attempt").asInt()).isEqualTo(2);
		assertThat(probe.path("httpStatus").isIntegralNumber()).isTrue();
		assertThat(probe.path("httpStatus").asInt()).isEqualTo(204);
		assertThat(probe.path("durationMs").isIntegralNumber()).isTrue();
		assertThat(probe.path("durationMs").asLong()).isEqualTo(17);
		assertThat(probe.has("traceId")).isFalse();
	}

	@Test
	void localConsoleRetainsReadableLogs() throws Exception {
		String output = runProbe("local");
		assertThat(output).contains("INFO", "Message with");
		assertThat(output.lines().anyMatch(line -> line.contains("Message with") && !line.startsWith("{"))).isTrue();
	}

	private String runProbe(String profile) throws Exception {
		Path output = directory.resolve(profile + ".log");
		Path errors = directory.resolve(profile + ".err");
		var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Xmx64m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				Probe.class.getName(), "--spring.profiles.active=" + profile)
				.redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
		try {
			assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("logging probe completed").isTrue();
			assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
			return Files.readString(output);
		}
		finally {
			process.destroyForcibly();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Probe {
		public static void main(String[] args) {
			var application = new SpringApplication(Probe.class);
			application.setWebApplicationType(WebApplicationType.NONE);
			try (var context = application.run(args);
					var ignored = CorrelationId.withId("json-probe-1")) {
				LoggerFactory.getLogger(Probe.class).atInfo().addKeyValue("event", "logging_probe")
						.addKeyValue("requestId", "ef9147a7-f092-46cb-ab55-d2f5dfb86bf0")
						.addKeyValue("attempt", 2).addKeyValue("httpStatus", 204).addKeyValue("durationMs", 17L)
						.log("Message with\na newline and \"quotes\"");
			}
		}
	}
}
