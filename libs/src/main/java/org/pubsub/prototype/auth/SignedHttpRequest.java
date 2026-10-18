package org.pubsub.prototype.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.pubsub.prototype.util.files.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** Signs HTTP method, path, body hash, receiver, timestamp, and nonce with a Cardano Ed25519 key. */
public final class SignedHttpRequest {
    public static final String ID_HEADER = "X-PubSub-Signer-Id";
    public static final String KEY_HEADER = "X-PubSub-Public-Key";
    public static final String TIME_HEADER = "X-PubSub-Timestamp";
    public static final String NONCE_HEADER = "X-PubSub-Nonce";
    public static final String SIGNATURE_HEADER = "X-PubSub-Signature";
    public static final long MAX_CLOCK_SKEW_MS = 60_000;
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String signerId;
    private final String publicKeyHex;
    private final PrivateKey privateKey;
    private final Clock clock;

    private SignedHttpRequest(String signerId, String publicKeyHex, PrivateKey privateKey, Clock clock) {
        this.signerId = signerId;
        this.publicKeyHex = publicKeyHex;
        this.privateKey = privateKey;
        this.clock = clock;
    }

    public static SignedHttpRequest load(Path signingKey, Path verificationKey, String expectedId) {
        try {
            byte[] encodedKey = readCardanoKey(verificationKey);
            String actualId = sha256(encodedKey);
            if (!actualId.equals(expectedId)) throw new IllegalArgumentException("signer ID does not match key");
            byte[] seed = rawKey(readCardanoKey(signingKey));
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            SignedHttpRequest signer = new SignedHttpRequest(actualId, HexFormat.of().formatHex(encodedKey),
                    privateKey, Clock.systemUTC());
            Headers sample = signer.sign("GET", "/", new byte[0], "self");
            if (!verify(sample, "GET", "/", new byte[0], "self", Set.of(actualId), Clock.systemUTC()))
                throw new IllegalArgumentException("signing and verification keys do not match");
            return signer;
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to load HTTP signing keys", ex);
        }
    }

    public static String keyId(Path verificationKey) {
        try {
            byte[] encodedKey = readCardanoKey(verificationKey);
            rawKey(encodedKey);
            return sha256(encodedKey);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load HTTP verification key", ex);
        }
    }

    public Headers sign(String method, String path, byte[] body, String audience) {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        long timestamp = clock.millis();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(message(method, path, body, audience, timestamp, nonce));
            return new Headers(signerId, publicKeyHex, timestamp, nonce,
                    Base64.getEncoder().encodeToString(signature.sign()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign HTTP request", ex);
        }
    }

    public static boolean verify(Headers headers, String method, String path, byte[] body, String audience,
                                 Set<String> allowedIds, Clock clock) {
        try {
            if (headers == null || !allowedIds.contains(headers.signerId())
                    || Math.abs(Math.subtractExact(clock.millis(), headers.timestamp())) > MAX_CLOCK_SKEW_MS)
                return false;
            if (Base64.getUrlDecoder().decode(headers.nonce()).length != 16) return false;
            byte[] encodedKey = HexFormat.of().parseHex(headers.publicKeyHex());
            if (!sha256(encodedKey).equals(headers.signerId())) return false;
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(concat(X509_PREFIX, rawKey(encodedKey))));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message(method, path, body, audience, headers.timestamp(), headers.nonce()));
            return verifier.verify(Base64.getDecoder().decode(headers.signature()));
        } catch (RuntimeException | GeneralSecurityException ex) {
            return false;
        }
    }

    private static byte[] message(String method, String path, byte[] body, String audience,
                                  long timestamp, String nonce) {
        String canonical = "pubsub-http-v1\n" + method + "\n" + path + "\n" + sha256(body) + "\n"
                + audience + "\n" + timestamp + "\n" + nonce;
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readCardanoKey(Path path) throws IOException {
        JsonNode json = JsonSupport.MAPPER.readTree(Files.readAllBytes(path));
        return HexFormat.of().parseHex(json.path("cborHex").asText());
    }

    private static byte[] rawKey(byte[] cbor) {
        if (cbor.length != 34 || cbor[0] != 0x58 || cbor[1] != 0x20)
            throw new IllegalArgumentException("expected a 32-byte Cardano Ed25519 key");
        return Arrays.copyOfRange(cbor, 2, cbor.length);
    }

    private static byte[] concat(byte[] prefix, byte[] key) {
        byte[] result = Arrays.copyOf(prefix, prefix.length + key.length);
        System.arraycopy(key, 0, result, prefix.length, key.length);
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record Headers(String signerId, String publicKeyHex, long timestamp, String nonce, String signature) { }
}
