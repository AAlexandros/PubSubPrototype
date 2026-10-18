package org.pubsub.prototype.event;

import org.pubsub.prototype.util.HexUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.pubsub.prototype.constants.EventConstants.TOPIC_ID_FIELD;
import static org.pubsub.prototype.constants.CryptoConstants.ED25519_ALGORITHM;
import static org.pubsub.prototype.constants.CryptoConstants.SHA_256_ALGORITHM;

public final class EventCrypto {
    private static final byte[] PROGRESS_DOMAIN = "pubsub-progress-v1".getBytes(StandardCharsets.US_ASCII);
    private EventCrypto() {
    }

    public static byte[] canonicalBody(EventEnvelope event) {
        byte[] topicId = HexUtils.decodeSha256(event.topicId(), TOPIC_ID_FIELD);
        byte[] publicKey = Base64.getDecoder().decode(event.publisherPublicKey());
        byte[] payload = Base64.getDecoder().decode(event.payload());
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(event.protocolVersion());
            out.write(topicId);
            out.writeInt(publicKey.length);
            out.write(publicKey);
            out.writeLong(event.sequenceNumber());
            out.writeLong(event.timestamp());
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to build canonical event body", ex);
        }
    }

    public static String eventId(EventEnvelope event) {
        return sha256Hex(canonicalBody(event));
    }

    public static String publisherKeyId(PublicKey publicKey) {
        return sha256Hex(publicKey.getEncoded());
    }

    public static String publisherKeyId(EventEnvelope event) {
        return sha256Hex(Base64.getDecoder().decode(event.publisherPublicKey()));
    }

    public static String sign(EventEnvelope event, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(ED25519_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(canonicalBody(event));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign event", ex);
        }
    }

    public static byte[] canonicalProgress(String topicId, String publisherPublicKey, long sequenceNumber,
                                           long timestamp) {
        try {
            byte[] publicKey = Base64.getDecoder().decode(publisherPublicKey);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(PROGRESS_DOMAIN);
            out.write(HexUtils.decodeSha256(topicId, TOPIC_ID_FIELD));
            out.writeInt(publicKey.length);
            out.write(publicKey);
            out.writeLong(sequenceNumber);
            out.writeLong(timestamp);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to build canonical progress body", ex);
        }
    }

    public static String signProgress(EventEnvelope event, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(ED25519_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(canonicalProgress(event.topicId(), event.publisherPublicKey(),
                    event.sequenceNumber(), event.timestamp()));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign publisher progress", ex);
        }
    }

    public static boolean verifyProgress(String topicId, String publisherPublicKey, long sequenceNumber,
                                         long timestamp, String signature) {
        try {
            PublicKey publicKey = KeyFactory.getInstance(ED25519_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publisherPublicKey)));
            Signature verifier = Signature.getInstance(ED25519_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(canonicalProgress(topicId, publisherPublicKey, sequenceNumber, timestamp));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (RuntimeException | GeneralSecurityException ex) {
            return false;
        }
    }

    public static boolean verify(EventEnvelope event) {
        try {
            PublicKey publicKey = KeyFactory.getInstance(ED25519_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(event.publisherPublicKey())));
            Signature verifier = Signature.getInstance(ED25519_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(canonicalBody(event));
            return verifier.verify(Base64.getDecoder().decode(event.signature()));
        } catch (RuntimeException | GeneralSecurityException ex) {
            return false;
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexUtils.encode(MessageDigest.getInstance(SHA_256_ALGORITHM).digest(bytes));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(SHA_256_ALGORITHM + " is not available", ex);
        }
    }
}
