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

import static org.pubsub.prototype.util.CryptoConstants.ED25519_ALGORITHM;
import static org.pubsub.prototype.util.ProtocolConstants.IDENTITY_FILE;
import static org.pubsub.prototype.util.ProtocolConstants.PRIVATE_KEY_FIELD;
import static org.pubsub.prototype.util.ProtocolConstants.PUBLIC_KEY_FIELD;
import static org.pubsub.prototype.util.Validators.requireNonNull;

/**
 * Persists a node's Ed25519 identity as {@code identity.json} in its configured directory.
 * The first startup creates the file; later startups reload the same key pair.
 *
 * IMPORTANT: If you want to reset the identity, just remove the identity.json file.
 */
public final class IdentityStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private record StoredIdentity(String publicKey, String privateKey) {
        private StoredIdentity {
            requireNonNull(publicKey, PUBLIC_KEY_FIELD);
            requireNonNull(privateKey, PRIVATE_KEY_FIELD);
        }
    }

    private IdentityStore() {
    }

    /**
     * Loads {@code identity.json} from {@code identityPath}, or creates it when it does not exist.
     */
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

    /**
     * Reads an already initialized identity from the specified file and returns the corresponding NodeIdentity.
     * This is intended to run any other time, after node initialization.
     *
     * @param file the path to the file where the identity is stored
     * @return the loaded NodeIdentity
     * @throws IOException
     * @throws GeneralSecurityException
     */
    private static NodeIdentity read(Path file) throws IOException, GeneralSecurityException {
        StoredIdentity stored = MAPPER.readValue(file.toFile(), StoredIdentity.class);
        KeyFactory factory = KeyFactory.getInstance(ED25519_ALGORITHM);
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(stored.publicKey)));
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.privateKey)));
        KeyPair keyPair = new KeyPair(publicKey, privateKey);
        return new NodeIdentity(keyPair, NodeId.fromPublicKey(publicKey));
    }

    /**
     * Writes a new identity to the specified file and returns the corresponding NodeIdentity.
     * This method tipically runs only one time, at the initialization of the node.
     *
     * @param file the path to the file where the new identity will be written
     * @return the newly created NodeIdentity
     * @throws IOException
     * @throws GeneralSecurityException
     */
    private static NodeIdentity writeNew(Path file) throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ED25519_ALGORITHM);
        KeyPair keyPair = generator.generateKeyPair();
        StoredIdentity stored = new StoredIdentity(
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
        );
        MAPPER.writeValue(file.toFile(), stored);
        return new NodeIdentity(keyPair, NodeId.fromPublicKey(keyPair.getPublic()));
    }
}
