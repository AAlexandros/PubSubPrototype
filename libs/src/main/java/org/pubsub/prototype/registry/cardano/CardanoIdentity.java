package org.pubsub.prototype.registry.cardano;

import org.pubsub.prototype.registry.RegistryConflictException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

record CardanoIdentity(String name, String address, Path signingKey, Path verificationKey) {
    static CardanoIdentity load(CardanoRegistryConfig config, String name) {
        Path keysDir = config.runtimeDir().toAbsolutePath().getParent().getParent()
                .resolve(CardanoRegistryNames.IdentityFile.KEYS_DIR.value())
                .resolve(name);
        Path addressFile = keysDir.resolve(CardanoRegistryNames.IdentityFile.PAYMENT_ADDRESS.value());
        Path signingKey = keysDir.resolve(CardanoRegistryNames.IdentityFile.PAYMENT_SIGNING_KEY.value());
        Path verificationKey = keysDir.resolve(CardanoRegistryNames.IdentityFile.PAYMENT_VERIFICATION_KEY.value());
        if (!Files.exists(addressFile) || !Files.exists(signingKey) || !Files.exists(verificationKey)) {
            throw new RegistryConflictException("Cardano identity files are missing for " + name);
        }
        try {
            return new CardanoIdentity(name, Files.readString(addressFile).trim(), signingKey, verificationKey);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Unable to read Cardano identity " + name, ex);
        }
    }

    String firstUtxo(CardanoCli cli) {
        return paymentUtxo(cli, Set.of());
    }

    String paymentUtxo(CardanoCli cli, Set<String> excludedRefs) {
        return utxos(cli).stream()
                .filter(utxo -> !excludedRefs.contains(utxo.ref()))
                .filter(CardanoIdentity::lovelaceOnly)
                .max(Comparator.comparingLong(CardanoIdentity::lovelace))
                .orElseThrow(() -> new RegistryConflictException("No payment UTxO is available for " + name))
                .ref();
    }

    String collateralUtxo(CardanoCli cli, Set<String> excludedRefs) {
        return utxos(cli).stream()
                .filter(utxo -> !excludedRefs.contains(utxo.ref()))
                .filter(CardanoIdentity::lovelaceOnly)
                .filter(utxo -> lovelace(utxo) > 0)
                .min(Comparator.comparingLong(CardanoIdentity::lovelace))
                .orElseThrow(() -> new RegistryConflictException("No collateral UTxO is available for " + name))
                .ref();
    }

    String vkeyHash(CardanoCli cli) {
        return cli.run("cardano-cli", "address", "key-hash", "--payment-verification-key-file", verificationKey.toString()).trim();
    }

    private List<CardanoScriptUtxo> utxos(CardanoCli cli) {
        Map<String, String> env = cli.readNetworkEnv();
        String magic = env.get(CardanoRegistryNames.RuntimeKey.NETWORK_MAGIC.value());
        String json = cli.run("cardano-cli", "query", "utxo", "--address", address, "--testnet-magic", magic, "--output-json");
        return new CardanoCliUtxoParser().parse(json);
    }

    private static boolean lovelaceOnly(CardanoScriptUtxo utxo) {
        return utxo.assets().keySet().stream().allMatch(CardanoRegistryNames.Asset.LOVELACE.value()::equals);
    }

    private static long lovelace(CardanoScriptUtxo utxo) {
        return utxo.assets().getOrDefault(CardanoRegistryNames.Asset.LOVELACE.value(), 0L);
    }
}
