package org.pubsub.prototype.auth;

import org.pubsub.prototype.protocol.HandshakeProof;
import org.pubsub.prototype.protocol.NodeId;
import org.pubsub.prototype.protocol.NodeIdentity;
import org.pubsub.prototype.protocol.ProtocolException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Proves both peers own the Ed25519 keys behind their claimed node IDs. */
public final class PeerHandshakeAuthenticator
        implements Authenticator<PeerHandshakeAuthenticator.Claim, NodeId> {
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Step { RESPONSE, FINISH }

    public record Claim(NodeId claimedId, HandshakeProof proof, Step step,
                        NodeId initiatorId, NodeId responderId,
                        String initiatorChallenge, String responderChallenge) { }

    public String freshChallenge() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public HandshakeProof hello(NodeIdentity identity, String challenge) {
        requireChallenge(challenge);
        return new HandshakeProof(publicKey(identity), challenge, null);
    }

    public HandshakeProof signed(NodeIdentity identity, Step step, String challenge,
                                 NodeId initiatorId, NodeId responderId,
                                 String initiatorChallenge, String responderChallenge) {
        requireChallenge(challenge);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(identity.keyPair().getPrivate());
            signer.update(message(step, initiatorId, responderId, initiatorChallenge, responderChallenge));
            return new HandshakeProof(publicKey(identity), challenge,
                    Base64.getEncoder().encodeToString(signer.sign()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign peer handshake", ex);
        }
    }

    public NodeId identify(NodeId claimedId, HandshakeProof proof) {
        try {
            if (proof == null) throw new IllegalArgumentException("missing handshake proof");
            requireChallenge(proof.challenge());
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(proof.publicKey())));
            if (!claimedId.equals(NodeId.fromPublicKey(key)))
                throw new IllegalArgumentException("peer key does not match node ID");
            return claimedId;
        } catch (RuntimeException | GeneralSecurityException ex) {
            throw new ProtocolException("Invalid peer identity", ex);
        }
    }

    @Override
    public NodeId authenticate(Claim claim) {
        identify(claim.claimedId(), claim.proof());
        try {
            String expected = claim.step() == Step.RESPONSE
                    ? claim.responderChallenge() : claim.initiatorChallenge();
            if (!expected.equals(claim.proof().challenge()))
                throw new IllegalArgumentException("handshake challenge mismatch");
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(claim.proof().publicKey())));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message(claim.step(), claim.initiatorId(), claim.responderId(),
                    claim.initiatorChallenge(), claim.responderChallenge()));
            if (!verifier.verify(Base64.getDecoder().decode(claim.proof().signature())))
                throw new IllegalArgumentException("invalid peer handshake signature");
            return claim.claimedId();
        } catch (RuntimeException | GeneralSecurityException ex) {
            throw new ProtocolException("Invalid peer handshake signature", ex);
        }
    }

    private static String publicKey(NodeIdentity identity) {
        return Base64.getEncoder().encodeToString(identity.keyPair().getPublic().getEncoded());
    }

    private static void requireChallenge(String challenge) {
        if (Base64.getUrlDecoder().decode(challenge).length != 24)
            throw new IllegalArgumentException("invalid handshake challenge");
    }

    private static byte[] message(Step step, NodeId initiatorId, NodeId responderId,
                                  String initiatorChallenge, String responderChallenge) {
        try {
            requireChallenge(initiatorChallenge);
            requireChallenge(responderChallenge);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("pubsub-peer-" + step.name() + "-v1");
            out.writeUTF(initiatorId.value());
            out.writeUTF(responderId.value());
            out.writeUTF(initiatorChallenge);
            out.writeUTF(responderChallenge);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to encode peer handshake", ex);
        }
    }
}
