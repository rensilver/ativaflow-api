import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/** Utilitário independente para desenvolvimento: java scripts/LocalJwt.java create|token. */
public class LocalJwt {

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("create")) {
            Path root = outsideGit(Path.of(System.getProperty("java.io.tmpdir")));
            Path directory = Files.createTempDirectory(root, "ativaflow-jwt.",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var keys = generator.generateKeyPair();
            write(directory.resolve("private.pem"), pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
            write(directory.resolve("public.pem"), pem("PUBLIC KEY", keys.getPublic().getEncoded()));
            var publicKey = (RSAPublicKey) keys.getPublic();
            Files.createDirectory(directory.resolve("jwks"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            write(directory.resolve("jwks/jwks.json"), """
                    {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s","n":"%s","e":"%s"}]}
                    """.formatted(kid(publicKey), unsigned(publicKey.getModulus()), unsigned(publicKey.getPublicExponent())));
            write(directory.resolve("env.sh"), """
                    export SPRING_PROFILES_ACTIVE=local
                    export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=https://ativaflow.local
                    export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=ativaflow-api
                    export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_PUBLICKEYLOCATION=%s
                    unset SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI
                    """.formatted(shellQuote(directory.resolve("public.pem").toUri().toString())));
            token(directory, "demo-consumer");
            // Apenas o caminho sai no console; o Bearer permanece em arquivo protegido.
            System.out.println(directory);
        } else if ((args.length == 2 || args.length == 3) && args[0].equals("token")) {
            token(outsideGit(Path.of(args[1])), args.length == 3 ? args[2] : "demo-consumer");
        } else {
            throw new IllegalArgumentException("Usage: java scripts/LocalJwt.java create | token <directory> [consumer]");
        }
    }

    private static void token(Path directory, String consumer) throws Exception {
        if (!consumer.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,99}")) {
            throw new IllegalArgumentException("Consumer must contain 1-100 characters: letters, digits, '.', '_', '@', '-'");
        }
        var factory = KeyFactory.getInstance("RSA");
        var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(readPem(directory.resolve("private.pem"))));
        var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(readPem(directory.resolve("public.pem"))));
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid(publicKey) + "\"}";
        String claims = """
                {"iss":"https://ativaflow.local","sub":"%s","aud":"ativaflow-api","iat":%d,"nbf":%d,"exp":%d}
                """.formatted(consumer, now, now - 5, now + 300).strip();
        String payload = encode(header.getBytes(StandardCharsets.UTF_8)) + "." + encode(claims.getBytes(StandardCharsets.UTF_8));
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(payload.getBytes(StandardCharsets.US_ASCII));
        write(directory.resolve("authorization.header"), "Authorization: Bearer " + payload + "." + encode(signer.sign()) + "\n");
    }

    private static Path outsideGit(Path path) throws Exception {
        Path real = path.toRealPath();
        for (Path parent = real; parent != null; parent = parent.getParent()) {
            if (Files.exists(parent.resolve(".git"))) {
                throw new IllegalArgumentException("JWT material must stay outside Git; choose an external java.io.tmpdir");
            }
        }
        return real;
    }

    private static void write(Path path, String contents) throws Exception {
        if (!Files.exists(path)) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(path, contents);
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] readPem(Path path) throws Exception {
        String encoded = Files.readString(path).replaceAll("-----[^\n]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(encoded);
    }

    private static String kid(RSAPublicKey key) throws Exception {
        return encode(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
    }

    private static String unsigned(BigInteger number) {
        byte[] bytes = number.toByteArray();
        return encode(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
