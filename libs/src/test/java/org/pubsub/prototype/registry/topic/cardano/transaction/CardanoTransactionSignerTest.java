package org.pubsub.prototype.registry.topic.cardano.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pubsub.prototype.cardano.cli.CacheOnlyCardanoCommandRunner;
import org.pubsub.prototype.cardano.cli.CardanoCliBackend;
import org.pubsub.prototype.registry.topic.cardano.CardanoRegistryConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CardanoTransactionSignerTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsIdentityLazilyAndCachesItUntilANewSignerIsCreated() throws IOException {
        Path runtimeDir = tempDir.resolve("devnet/runtime/registry");
        CardanoRegistryConfig config = new CardanoRegistryConfig(
                runtimeDir,
                "registry-deployer",
                CardanoCliBackend.CACHE_ONLY
        );
        CardanoTransactionSigner signer = signer(config);
        Path addressFile = createIdentity(runtimeDir, config.signer(), "address-1");

        CardanoIdentity first = signer.identity();
        Files.writeString(addressFile, "address-2");

        assertSame(first, signer.identity());
        assertEquals("address-1", signer.identity().address());
        assertEquals("address-2", signer(config).identity().address());
    }

    private static CardanoTransactionSigner signer(CardanoRegistryConfig config) {
        return new CardanoTransactionSigner(
                config,
                new CacheOnlyCardanoCommandRunner(),
                new CardanoRegistryIdentityValidator()
        );
    }

    private static Path createIdentity(Path runtimeDir, String name, String address) throws IOException {
        Path identityDir = runtimeDir.toAbsolutePath().getParent().getParent().resolve("keys").resolve(name);
        Files.createDirectories(identityDir);
        Path addressFile = identityDir.resolve("payment.addr");
        Files.writeString(addressFile, address);
        Files.writeString(identityDir.resolve("payment.skey"), "signing-key");
        Files.writeString(identityDir.resolve("payment.vkey"), "verification-key");
        return addressFile;
    }
}
