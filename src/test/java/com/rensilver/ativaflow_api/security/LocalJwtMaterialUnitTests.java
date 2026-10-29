package com.rensilver.ativaflow_api.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercita o utilitário real em processos isolados, sem API, banco ou rede. */
class LocalJwtMaterialUnitTests {

	private static final Path TOOL = Path.of("scripts/LocalJwt.java").toAbsolutePath();
	@TempDir
	Path temporary;

	@Test
	void createsEphemeralMaterialAcceptedByLocalPublicKeyDecoder() throws Exception {
		Path material = create();
		var properties = new OAuth2ResourceServerProperties();
		properties.getJwt().setIssuerUri("https://ativaflow.local");
		properties.getJwt().setAudiences(List.of("ativaflow-api"));
		properties.getJwt().setPublicKeyLocation(new FileSystemResource(material.resolve("public.pem")));

		var jwt = new LocalJwtDecoderConfiguration().localJwtDecoder(properties).decode(token(material));
		assertThat(jwt.getSubject()).isEqualTo("demo-consumer");
		assertThat(jwt.getExpiresAt()).isBetween(Instant.now(), Instant.now().plusSeconds(301));
		assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(300);
		assertThat(Files.readString(material.resolve("env.sh")))
				.contains("SPRING_PROFILES_ACTIVE=local", "PUBLICKEYLOCATION='" + material.resolve("public.pem").toUri(),
						"unset SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI");
	}

	@Test
	void publishesOnlyPublicJwksMatchingTokenKidAndSignature() throws Exception {
		Path material = create();
		var jwks = JWKSet.parse(Files.readString(material.resolve("jwks/jwks.json")));
		assertThat(jwks.getKeys()).hasSize(1);
		var key = (RSAKey) jwks.getKeys().getFirst();
		var jwt = SignedJWT.parse(token(material));
		assertThat(key.isPrivate()).isFalse();
		assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
		assertThat(jwt.getHeader().getKeyID()).isEqualTo(key.getKeyID());
		assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();
		try (var files = Files.list(material.resolve("jwks"))) {
			assertThat(files.map(path -> path.getFileName().toString())).containsExactly("jwks.json");
		}
	}

	@Test
	void renewsTokenForAnotherConsumerWithoutRotatingKeys() throws Exception {
		Path material = create();
		String publicKey = Files.readString(material.resolve("public.pem"));
		var result = run(temporary, "token", material.toString(), "another-consumer");
		assertThat(result.exitCode()).as(result.output()).isZero();
		assertThat(result.output()).isEmpty();
		assertThat(Files.readString(material.resolve("public.pem"))).isEqualTo(publicKey);
		var jwt = SignedJWT.parse(token(material));
		assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("another-consumer");
		var key = (RSAKey) JWKSet.parse(Files.readString(material.resolve("jwks/jwks.json"))).getKeys().getFirst();
		assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();
	}

	@Test
	void restrictsAccessToDirectoryPrivateKeyAndBearerHeader() throws Exception {
		Path material = create();
		assertThat(Files.getPosixFilePermissions(material)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
		for (String file : List.of("private.pem", "public.pem", "authorization.header", "env.sh", "jwks/jwks.json")) {
			assertThat(Files.getPosixFilePermissions(material.resolve(file)))
					.isEqualTo(PosixFilePermissions.fromString("rw-------"));
		}
	}

	@Test
	void refusesTemporaryDirectoryInsideGitCheckout() throws Exception {
		Path checkout = Files.createDirectory(temporary.resolve("checkout"));
		Files.writeString(checkout.resolve(".git"), "gitdir: /unused/worktree");
		var result = run(checkout, "create");
		assertThat(result.exitCode()).isNotZero();
		assertThat(result.output()).contains("outside Git");
		try (var files = Files.list(checkout)) {
			assertThat(files.map(path -> path.getFileName().toString())).containsExactly(".git");
		}
	}

	private Path create() throws Exception {
		var result = run(temporary, "create");
		assertThat(result.exitCode()).as(result.output()).isZero();
		Path material = Path.of(result.output().strip());
		assertThat(material).startsWith(temporary.toRealPath());
		return material;
	}

	private String token(Path material) throws Exception {
		String header = Files.readString(material.resolve("authorization.header"));
		assertThat(header).startsWith("Authorization: Bearer ");
		return header.substring("Authorization: Bearer ".length()).strip();
	}

	private Result run(Path tempRoot, String... arguments) throws Exception {
		var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
				"-Djava.io.tmpdir=" + tempRoot, TOOL.toString()));
		command.addAll(List.of(arguments));
		var process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		return new Result(process.waitFor(), output);
	}

	private record Result(int exitCode, String output) {}
}
