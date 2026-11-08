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

/**
 * Creates and verifies the signature headers used between replication servers.
 * The signed payload contains the HTTP method, raw path and query, SHA-256 body
 * hash, receiver server ID, timestamp, and nonce, in that order. The receiver
 * server ID prevents a request signed for one server from being replayed at another.
 *
 * <p>The nested {@link RequestSigner} holds one server's private key and clock.
 * Verification here checks signature, active signer ID, and timestamp freshness;
 * {@link HttpRequestAuthenticator} separately rejects a reused nonce.</p>
 */
public final class ReplicationHttpRequestSignatures {
    public static final String SERVER_ID_HEADER = "X-PubSub-Signer-Id";
    public static final String PUBLIC_KEY_HEADER = "X-PubSub-Public-Key";
    public static final String TIMESTAMP_HEADER = "X-PubSub-Timestamp";
    public static final String NONCE_HEADER = "X-PubSub-Nonce";
    public static final String SIGNATURE_HEADER = "X-PubSub-Signature";
    public static final long MAX_CLOCK_SKEW_MS = 60_000;

    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ReplicationHttpRequestSignatures() { }

    /**
     * Loads Cardano Ed25519 signing and verification key files, checks that the
     * verification key hashes to {@code expectedServerId}, and proves the two
     * keys match by signing and verifying a sample request.
     *
     * @param signingKeyFile Cardano signing key JSON containing {@code cborHex}
     * @param verificationKeyFile Cardano verification key JSON containing {@code cborHex}
     * @param expectedServerId registered SHA-256 ID of the verification key
     * @return signer for this server's internal replication requests
     * @throws IllegalArgumentException if the key IDs or key pair do not match
     * @throws IllegalStateException if a key file cannot be read or decoded
     */
    public static RequestSigner loadSignerFromCardanoKeys(Path signingKeyFile, Path verificationKeyFile,
                                                          String expectedServerId) {
        try {
            byte[] encodedKey = readCardanoKeyCbor(verificationKeyFile);
            String actualServerId = sha256Hex(encodedKey);
            if (!actualServerId.equals(expectedServerId))
                throw new IllegalArgumentException("signer ID does not match key");
            byte[] seed = extractRawEd25519Key(readCardanoKeyCbor(signingKeyFile));
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            RequestSigner signer = new RequestSigner(actualServerId, HexFormat.of().formatHex(encodedKey),
                    privateKey, Clock.systemUTC());
            SignatureHeaders sample = signer.createSignatureHeaders("GET", "/", new byte[0], "self");
            if (!verifyRequestSignature(sample, "GET", "/", new byte[0], "self",
                    Set.of(actualServerId), Clock.systemUTC()))
                throw new IllegalArgumentException("signing and verification keys do not match");
            return signer;
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to load HTTP signing keys", ex);
        }
    }

    /** Returns the SHA-256 server ID derived from a Cardano verification key file. */
    public static String serverIdFromVerificationKey(Path verificationKeyFile) {
        try {
            byte[] encodedKey = readCardanoKeyCbor(verificationKeyFile);
            extractRawEd25519Key(encodedKey);
            return sha256Hex(encodedKey);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load HTTP verification key", ex);
        }
    }

    /**
     * Verifies a request signature against the exact method, raw path and query,
     * body bytes, intended receiver, active signer IDs, and current time.
     * Returns {@code false} for malformed headers, an unknown signer, an invalid
     * signature, or a timestamp more than one minute from the receiver's clock.
     * Nonce replay detection belongs to {@link HttpRequestAuthenticator}.
     *
     * @param headers signature fields read from the HTTP request headers
     * @param method exact HTTP method received
     * @param rawPathAndQuery raw URI path with its query string, if present
     * @param body exact request body bytes received
     * @param receiverServerId registered ID of the receiving server
     * @param activeServerIds IDs currently allowed to send internal requests
     * @param clock receiving server's clock
     * @return whether the signature and freshness checks pass
     */
    public static boolean verifyRequestSignature(SignatureHeaders headers, String method, String rawPathAndQuery,
                                                 byte[] body, String receiverServerId, Set<String> activeServerIds,
                                                 Clock clock) {
        try {
            if (headers == null || !activeServerIds.contains(headers.signerServerId())
                    || Math.abs(Math.subtractExact(clock.millis(), headers.timestampMillis())) > MAX_CLOCK_SKEW_MS)
                return false;
            if (Base64.getUrlDecoder().decode(headers.nonce()).length != 16) return false;
            byte[] encodedKey = HexFormat.of().parseHex(headers.publicKeyHex());
            if (!sha256Hex(encodedKey).equals(headers.signerServerId())) return false;
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(concat(X509_PREFIX, extractRawEd25519Key(encodedKey))));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(canonicalSignaturePayload(method, rawPathAndQuery, body, receiverServerId,
                    headers.timestampMillis(), headers.nonce()));
            return verifier.verify(Base64.getDecoder().decode(headers.signatureBase64()));
        } catch (RuntimeException | GeneralSecurityException ex) {
            return false;
        }
    }

    /**
     * Headers carried with a signed internal replication request.
     *
     * @param signerServerId SHA-256 ID of the signing server's Cardano verification key
     * @param publicKeyHex hexadecimal CBOR encoding of the verification key
     * @param timestampMillis signing time in Unix milliseconds
     * @param nonce URL-safe base64 encoding of 16 random bytes
     * @param signatureBase64 base64 Ed25519 signature over the canonical request payload
     */
    public record SignatureHeaders(String signerServerId, String publicKeyHex, long timestampMillis,
                                   String nonce, String signatureBase64) { }

    /** Holds the private key used to sign requests sent by one replication server. */
    public static final class RequestSigner {
        private final String signerServerId;
        private final String publicKeyHex;
        private final PrivateKey privateKey;
        private final Clock clock;

        private RequestSigner(String signerServerId, String publicKeyHex, PrivateKey privateKey, Clock clock) {
            this.signerServerId = signerServerId;
            this.publicKeyHex = publicKeyHex;
            this.privateKey = privateKey;
            this.clock = clock;
        }

        /**
         * Signs the exact HTTP method, raw path and query, body bytes, and receiver
         * server ID. Each call adds a fresh timestamp and random 16-byte nonce.
         *
         * @return the five signature fields to attach as HTTP headers
         */
        public SignatureHeaders createSignatureHeaders(String method, String rawPathAndQuery, byte[] body,
                                                       String receiverServerId) {
            byte[] random = new byte[16];
            RANDOM.nextBytes(random);
            long timestamp = clock.millis();
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(privateKey);
                signature.update(canonicalSignaturePayload(method, rawPathAndQuery, body, receiverServerId,
                        timestamp, nonce));
                return new SignatureHeaders(signerServerId, publicKeyHex, timestamp, nonce,
                        Base64.getEncoder().encodeToString(signature.sign()));
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Unable to sign HTTP request", ex);
            }
        }
    }

    private static byte[] canonicalSignaturePayload(String method, String rawPathAndQuery, byte[] body,
                                                    String receiverServerId, long timestamp, String nonce) {
        String canonical = "pubsub-http-v1\n" + method + "\n" + rawPathAndQuery + "\n" + sha256Hex(body) + "\n"
                + receiverServerId + "\n" + timestamp + "\n" + nonce;
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readCardanoKeyCbor(Path path) throws IOException {
        JsonNode json = JsonSupport.MAPPER.readTree(Files.readAllBytes(path));
        return HexFormat.of().parseHex(json.path("cborHex").asText());
    }

    private static byte[] extractRawEd25519Key(byte[] cbor) {
        if (cbor.length != 34 || cbor[0] != 0x58 || cbor[1] != 0x20)
            throw new IllegalArgumentException("expected a 32-byte Cardano Ed25519 key");
        return Arrays.copyOfRange(cbor, 2, cbor.length);
    }

    private static byte[] concat(byte[] prefix, byte[] key) {
        byte[] result = Arrays.copyOf(prefix, prefix.length + key.length);
        System.arraycopy(key, 0, result, prefix.length, key.length);
        return result;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
