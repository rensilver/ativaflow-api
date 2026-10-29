package com.rensilver.ativaflow_api.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAccessTokenMaterialUnitTests {

	@TempDir
	Path temporary;

	@Test
	void createsAndRenewsExplicitAccessTokensAcceptedByConfiguredDecoder() throws Exception {
		Path material = Path.of(run("create").strip());
		var properties = new OAuth2ResourceServerProperties();
		properties.getJwt().setIssuerUri("https://ativaflow.local");
		properties.getJwt().setAudiences(List.of("ativaflow-api"));
		properties.getJwt().setPublicKeyLocation(new FileSystemResource(material.resolve("public.pem")));
		assertAccessToken(material, properties, "demo-consumer");

		assertThat(run("token", material.toString(), "another-consumer")).isEmpty();
		assertAccessToken(material, properties, "another-consumer");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void localTokensGrantReadAndWriteScopes(boolean renewed) throws Exception {
		Path material = Path.of(run("create").strip());
		if (renewed) {
			run("token", material.toString(), "another-consumer");
		}
		var properties = new OAuth2ResourceServerProperties();
		properties.getJwt().setIssuerUri("https://ativaflow.local");
		properties.getJwt().setAudiences(List.of("ativaflow-api"));
		properties.getJwt().setPublicKeyLocation(new FileSystemResource(material.resolve("public.pem")));
		String header = Files.readString(material.resolve("authorization.header"));
		String token = header.substring("Authorization: Bearer ".length()).strip();
		var jwt = new LocalJwtDecoderConfiguration().localJwtDecoder(properties).decode(token);

		var authentication = new JwtAuthenticationConverter().convert(jwt);

		assertThat(authentication.getAuthorities()).extracting(authority -> authority.getAuthority())
				.containsExactlyInAnyOrder("SCOPE_requests/read", "SCOPE_requests/write");
	}

	private void assertAccessToken(Path material, OAuth2ResourceServerProperties properties, String consumer) throws Exception {
		String header = Files.readString(material.resolve("authorization.header"));
		String token = header.substring("Authorization: Bearer ".length()).strip();
		assertThat(SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("token_use")).isEqualTo("access");
		assertThat(new LocalJwtDecoderConfiguration().localJwtDecoder(properties).decode(token).getSubject())
				.isEqualTo(consumer);
	}

	private String run(String... arguments) throws Exception {
		var command = new java.util.ArrayList<>(List.of(
				Path.of(System.getProperty("java.home"), "bin/java").toString(),
				"-Djava.io.tmpdir=" + temporary, Path.of("scripts/LocalJwt.java").toAbsolutePath().toString()));
		command.addAll(List.of(arguments));
		var process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as(output).isZero();
		return output;
	}
}
