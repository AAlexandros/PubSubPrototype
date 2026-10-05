package org.pubsub.prototype.registry.cardano.transaction;

import org.pubsub.prototype.registry.RegistryConflictException;
import org.pubsub.prototype.registry.cardano.CardanoRegistryConfig;
import org.pubsub.prototype.registry.cardano.datum.CardanoCliUtxoParser;
import org.pubsub.prototype.registry.cardano.datum.CardanoScriptUtxo;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.util.TextFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A Cardano payment identity used by registry transactions.
 *
 * <p>The payment address is stored as its textual value because it is passed directly to Cardano CLI
 * commands (for example, {@code query utxo --address}). {@code payment.addr} encodes a payment credential
 * and network information; for this identity it is derived from the payment verification key. The signing
 * and verification keys are retained as paths because Cardano CLI reads their files directly. The signing
 * key signs transaction bodies, while the verification key is used here to derive the public payment-key
 * hash used to identify the required signer.
 */
record CardanoIdentity(String name, String address, Path signingKey, Path verificationKey) {
    private static final String KEYS_DIRECTORY = "keys";
    // Textual payment address, containing the payment credential and network information.
    private static final String PAYMENT_ADDRESS_FILE = "payment.addr";
    // Private payment signing key, used to sign transactions.
    private static final String PAYMENT_SIGNING_KEY_FILE = "payment.skey";
    // Public payment verification key, used to derive the required signer hash.
    private static final String PAYMENT_VERIFICATION_KEY_FILE = "payment.vkey";
    private static final String LOVELACE_ASSET = "lovelace";

    static CardanoIdentity load(CardanoRegistryConfig config) {
        String name = config.signer();
        // Only the configured transaction signer is loaded from the local identity directory.
        Path keysDir = config.runtimeDir().toAbsolutePath().getParent().getParent()
                .resolve(KEYS_DIRECTORY)
                .resolve(name);
        Path addressFile = keysDir.resolve(PAYMENT_ADDRESS_FILE);
        Path signingKey = keysDir.resolve(PAYMENT_SIGNING_KEY_FILE);
        Path verificationKey = keysDir.resolve(PAYMENT_VERIFICATION_KEY_FILE);
        if (!Files.exists(addressFile) || !Files.exists(signingKey) || !Files.exists(verificationKey)) {
            throw new RegistryConflictException("Cardano identity files are missing for " + name);
        }
        return new CardanoIdentity(name, TextFiles.read(addressFile).trim(), signingKey, verificationKey);
    }

    /**
     * Selects the largest available ADA-only UTxO as the ordinary funding input for a transaction.
     *
     * <p>This is a simple coin-selection policy, not a Cardano protocol requirement. Choosing a single
     * large input makes it likely that the transaction can cover its outputs and fee without combining
     * several inputs. References in {@code excludedRefs} cannot be reused, for example when one UTxO has
     * already been selected as collateral.
     */
    String paymentUtxo(CardanoCommandRunner commandRunner, Set<String> excludedRefs) {
        return utxos(commandRunner).stream()
                .filter(utxo -> !excludedRefs.contains(utxo.ref()))
                .filter(CardanoIdentity::lovelaceOnly)
                // Prefer the UTxO with the most lovelace for the normal --tx-in.
                .max(Comparator.comparingLong(CardanoIdentity::lovelace))
                .orElseThrow(() -> new RegistryConflictException("No payment UTxO is available for " + name))
                .ref();
    }

    /**
     * Selects the smallest positive ADA-only UTxO for Cardano CLI's {@code --tx-in-collateral} input.
     *
     * <p>Collateral is separate from the normal payment input and is used only if script validation fails.
     * Selecting the smallest eligible UTxO limits the ADA exposed as collateral. The positive-value check is
     * explicit because this method chooses a minimum; {@link #lovelaceOnly(CardanoScriptUtxo)} only rejects
     * non-ADA assets and does not guarantee a positive lovelace amount.
     */
    String collateralUtxo(CardanoCommandRunner commandRunner, Set<String> excludedRefs) {
        return utxos(commandRunner).stream()
                .filter(utxo -> !excludedRefs.contains(utxo.ref()))
                .filter(CardanoIdentity::lovelaceOnly)
                // Collateral must contain a positive lovelace amount.
                .filter(utxo -> lovelace(utxo) > 0)
                // Use the smallest eligible collateral UTxO.
                .min(Comparator.comparingLong(CardanoIdentity::lovelace))
                .orElseThrow(() -> new RegistryConflictException("No collateral UTxO is available for " + name))
                .ref();
    }

    /**
     * Derives this identity's public payment-key hash from {@code payment.vkey}.
     *
     * <p>The hash is a stable public identifier for the key, not a transaction hash or a signature. The
     * transaction builder supplies it as {@code --required-signer-hash}; Cardano then verifies that the
     * signed transaction contains a witness for the corresponding key.
     */
    String vkeyHash(CardanoCommandRunner commandRunner) {
        return commandRunner.run(
                "address", "key-hash", "--payment-verification-key-file", verificationKey.toString()
        ).trim();
    }

    /** Queries the node for all UTxOs at this identity's payment address. */
    private List<CardanoScriptUtxo> utxos(CardanoCommandRunner commandRunner) {
        // Cardano CLI returns the address's UTxOs as JSON.
        String json = commandRunner.run("query", "utxo", "--address", address,
                "--testnet-magic", commandRunner.networkMagic(), "--output-json");
        // Convert the CLI response to the application's UTxO representation.
        return new CardanoCliUtxoParser().parse(json);
    }

    /**
     * Returns whether every listed asset is lovelace, with no native tokens present.
     *
     * <p>This predicate does not check that a lovelace entry exists or that its amount is positive; callers
     * that need positive ADA must check {@link #lovelace(CardanoScriptUtxo)} separately.
     */
    private static boolean lovelaceOnly(CardanoScriptUtxo utxo) {
        return utxo.assets().keySet().stream().allMatch(LOVELACE_ASSET::equals);
    }

    private static long lovelace(CardanoScriptUtxo utxo) {
        return utxo.assets().getOrDefault(LOVELACE_ASSET, 0L);
    }
}
