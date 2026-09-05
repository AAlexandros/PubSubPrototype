package org.pubsub.prototype.event;

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
import java.util.Base64;

public final class EventCrypto {
    private static final String DIGEST = "SHA-256";
    private static final String SIGNATURE = "Ed25519";

    private EventCrypto() {
    }

    public static byte[] canonicalBody(EventEnvelope event) {
        byte[] topicId = EventHex.decode(event.topicId());
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
            Signature signature = Signature.getInstance(SIGNATURE);
            signature.initSign(privateKey);
            signature.update(canonicalBody(event));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign event", ex);
        }
    }

    public static boolean verify(EventEnvelope event) {
        try {
            PublicKey publicKey = KeyFactory.getInstance(SIGNATURE)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(event.publisherPublicKey())));
            Signature verifier = Signature.getInstance(SIGNATURE);
            verifier.initVerify(publicKey);
            verifier.update(canonicalBody(event));
            return verifier.verify(Base64.getDecoder().decode(event.signature()));
        } catch (RuntimeException | GeneralSecurityException ex) {
            return false;
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return EventHex.encode(MessageDigest.getInstance(DIGEST).digest(bytes));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(DIGEST + " is not available", ex);
        }
    }
}
