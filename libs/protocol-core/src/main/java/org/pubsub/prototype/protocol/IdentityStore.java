package org.pubsub.prototype.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class IdentityStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String IDENTITY_FILE = "identity.json";
    private static final String KEY_GENERATION_ALGORITHM = "Ed25519";

    private IdentityStore() {
    }

    public static NodeIdentity loadOrCreate(Path identityPath) {
        try {
            Files.createDirectories(identityPath);
            Path file = identityPath.resolve(IDENTITY_FILE);
            if (Files.exists(file)) {
                return read(file);
            }
            return writeNew(file);
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to load or create node identity at " + identityPath, ex);
        }
    }

    private static NodeIdentity read(Path file) throws IOException, GeneralSecurityException {
        StoredIdentity stored = MAPPER.readValue(file.toFile(), StoredIdentity.class);
        KeyFactory factory = KeyFactory.getInstance(KEY_GENERATION_ALGORITHM);
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(stored.publicKey)));
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.privateKey)));
        KeyPair keyPair = new KeyPair(publicKey, privateKey);
        return new NodeIdentity(keyPair, NodeId.fromPublicKey(publicKey));
    }

    private static NodeIdentity writeNew(Path file) throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_GENERATION_ALGORITHM);
        KeyPair keyPair = generator.generateKeyPair();
        StoredIdentity stored = new StoredIdentity(
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
        );
        MAPPER.writeValue(file.toFile(), stored);
        return new NodeIdentity(keyPair, NodeId.fromPublicKey(keyPair.getPublic()));
    }

    private record StoredIdentity(String publicKey, String privateKey) {
        private StoredIdentity {
            if (publicKey == null || privateKey == null) {
                throw new IllegalArgumentException("Stored identity is incomplete");
            }
        }
    }
}
