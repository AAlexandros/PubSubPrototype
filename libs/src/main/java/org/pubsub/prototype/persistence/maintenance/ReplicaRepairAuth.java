package org.pubsub.prototype.persistence.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.util.files.JsonSupport;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
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
import java.util.List;
import java.util.Set;

/** Signs repair notices with the Cardano key whose hash is the registered server ID. */
public final class ReplicaRepairAuth {
    public static final String SERVER_ID_HEADER = "X-Replication-Server-Id";
    public static final String PUBLIC_KEY_HEADER = "X-Replication-Public-Key";
    public static final String TIMESTAMP_HEADER = "X-Replication-Timestamp";
    public static final String NONCE_HEADER = "X-Replication-Nonce";
    public static final String SIGNATURE_HEADER = "X-Replication-Signature";
    public static final long MAX_CLOCK_SKEW_MS = 60_000;
    private static final byte[] X509_ED25519_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String serverId;
    private final String verificationKeyHex;
    private final PrivateKey signingKey;
    private final Clock clock;

    private ReplicaRepairAuth(String serverId, String verificationKeyHex, PrivateKey signingKey, Clock clock) {
        this.serverId = serverId;
        this.verificationKeyHex = verificationKeyHex;
        this.signingKey = signingKey;
        this.clock = clock;
    }

    public static ReplicaRepairAuth load(Path signingKeyFile, Path verificationKeyFile, String serverId) {
        try {
            byte[] encodedVerificationKey = cardanoKey(verificationKeyFile);
            if (!EventCrypto.sha256Hex(encodedVerificationKey).equals(serverId))
                throw new IllegalArgumentException("verification key does not match registered server ID");
            byte[] seed = rawKey(cardanoKey(signingKeyFile));
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            String verificationKeyHex = HexFormat.of().formatHex(encodedVerificationKey);
            ReplicaRepairAuth auth = new ReplicaRepairAuth(serverId, verificationKeyHex, privateKey, Clock.systemUTC());
            ReplicaRepairRequest sample = new ReplicaRepairRequest(ReplicaRecordType.TOPIC_LOG,
                    "0".repeat(64), "0".repeat(64), List.of(serverId), List.of());
            SignedRequest signed = auth.sign(sample, serverId);
            if (!verify(sample, signed, Set.of(serverId), serverId, Clock.systemUTC()))
                throw new IllegalArgumentException("signing and verification keys do not match");
            return auth;
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to load replication repair signing keys", ex);
        }
    }

    public SignedRequest sign(ReplicaRepairRequest request, String targetServerId) {
        long timestamp = clock.millis();
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey);
            signer.update(message(request, serverId, targetServerId, timestamp, nonce));
            return new SignedRequest(serverId, verificationKeyHex, timestamp, nonce,
                    Base64.getEncoder().encodeToString(signer.sign()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign replication repair request", ex);
        }
    }

    public static boolean verify(ReplicaRepairRequest request, SignedRequest signed, Set<String> activeServerIds,
                                 String targetServerId, Clock clock) {
        try {
            if (signed == null || !activeServerIds.contains(signed.serverId())
                    || Math.abs(Math.subtractExact(clock.millis(), signed.timestamp())) > MAX_CLOCK_SKEW_MS)
                return false;
            byte[] encodedKey = HexFormat.of().parseHex(signed.verificationKeyHex());
            if (!EventCrypto.sha256Hex(encodedKey).equals(signed.serverId())) return false;
            PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(concat(X509_ED25519_PREFIX, rawKey(encodedKey))));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message(request, signed.serverId(), targetServerId, signed.timestamp(), signed.nonce()));
            return verifier.verify(Base64.getDecoder().decode(signed.signature()));
        } catch (RuntimeException | GeneralSecurityException ex) {
            return false;
        }
    }

    private static byte[] cardanoKey(Path path) throws IOException {
        JsonNode json = JsonSupport.MAPPER.readTree(Files.readAllBytes(path));
        return HexFormat.of().parseHex(json.path("cborHex").asText());
    }

    private static byte[] rawKey(byte[] encoded) {
        if (encoded.length != 34 || encoded[0] != 0x58 || encoded[1] != 0x20)
            throw new IllegalArgumentException("expected a 32-byte Cardano Ed25519 key");
        return Arrays.copyOfRange(encoded, 2, encoded.length);
    }

    private static byte[] concat(byte[] prefix, byte[] key) {
        byte[] result = Arrays.copyOf(prefix, prefix.length + key.length);
        System.arraycopy(key, 0, result, prefix.length, key.length);
        return result;
    }

    private static byte[] message(ReplicaRepairRequest request, String serverId, String targetServerId,
                                  long timestamp, String nonce) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("pubsub-replica-repair-v1");
            out.writeUTF(serverId);
            out.writeUTF(targetServerId);
            out.writeLong(timestamp);
            out.writeUTF(nonce);
            out.writeUTF(request.recordType().name());
            out.writeUTF(request.recordKey());
            out.writeUTF(request.topicId());
            out.writeInt(request.sourceServerIds().size());
            for (String id : request.sourceServerIds()) out.writeUTF(id);
            out.writeInt(request.unavailableServerIds().size());
            for (String id : request.unavailableServerIds()) out.writeUTF(id);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to encode repair request", ex);
        }
    }

    public record SignedRequest(String serverId, String verificationKeyHex, long timestamp, String nonce,
                                String signature) { }
}
