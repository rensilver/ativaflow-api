package com.rensilver.ativaflow_api.observability;

import java.sql.SQLException;

import javax.sql.DataSource;

import com.rensilver.ativaflow_api.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.lang.NonNull;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
		"DB_PASSWORD=probe-test",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
		"spring.security.oauth2.resourceserver.jwt.audiences=ativaflow-api"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "test"})
class HealthProbesIntegrationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ConfigurableApplicationContext context;

	@MockitoSpyBean
	private DataSource database;

	@AfterEach
	void restoreAvailability() {
		AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
		AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
	}

	@ParameterizedTest
	@ValueSource(strings = {"liveness", "readiness"})
	void probesRemainPublicAndUpWithHealthyDatabaseAndUnreachableProvider(@NonNull String group) throws Exception {
		// The test profile points the real provider client at localhost:1 (no server).
		mvc.perform(get("/actuator/health/" + group))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\"}", true));
		mvc.perform(get("/actuator/health/" + group).with(jwt().jwt(token -> token.subject("monitor"))))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\"}", true));
	}

	@Test
	void brokenApplicationMakesOnlyLivenessFail() throws Exception {
		AvailabilityChangeEvent.publish(context, LivenessState.BROKEN);

		mvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().json("{\"status\":\"DOWN\"}", true));
		mvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk());
	}

	@Test
	void refusingTrafficMakesOnlyReadinessFail() throws Exception {
		AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);

		mvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().json("{\"status\":\"OUT_OF_SERVICE\"}", true));
		mvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk());
	}

	@Test
	void unavailableDatabaseStopsReadinessWithoutFailingLivenessOrDisclosingDetails() throws Exception {
		// Fail only the external connection; the real database indicator and groups still execute.
		doThrow(new SQLException("private-database-info")).when(database).getConnection();

		mvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().json("{\"status\":\"DOWN\"}", true));
		mvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"status\":\"UP\"}", true));
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = {"env", "configprops", "heapdump", "metrics", "info"})
	void otherActuatorEndpointsRemainProtectedAndUnexposed(@NonNull String endpoint) throws Exception {
		mvc.perform(get("/actuator/" + endpoint))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/" + endpoint).with(jwt().jwt(token -> token.subject("monitor"))))
				.andExpect(status().isNotFound());
	}
}
