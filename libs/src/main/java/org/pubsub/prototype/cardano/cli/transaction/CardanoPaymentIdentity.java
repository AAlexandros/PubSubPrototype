package org.pubsub.prototype.cardano.cli.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.pubsub.prototype.cardano.cli.CardanoCommandRunner;
import org.pubsub.prototype.event.EventCrypto;
import org.pubsub.prototype.util.JsonSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Local Cardano payment identity used to fund and sign transactions. */
public record CardanoPaymentIdentity(String name, String address, Path signingKey, Path verificationKey) {
    private static final String LOVELACE = "lovelace";
    private static final ObjectMapper MAPPER = JsonSupport.MAPPER;

    public static CardanoPaymentIdentity load(Path keysDirectory, String name) {
        Path identityDir = keysDirectory.resolve(name);
        Path addressFile = identityDir.resolve("payment.addr");
        Path signingKey = identityDir.resolve("payment.skey");
        Path verificationKey = identityDir.resolve("payment.vkey");
        if (!Files.isRegularFile(addressFile) || !Files.isRegularFile(signingKey)
                || !Files.isRegularFile(verificationKey)) {
            throw new IllegalStateException("Cardano identity files are missing for " + name);
        }
        try {
            return new CardanoPaymentIdentity(name, Files.readString(addressFile).trim(), signingKey, verificationKey);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Cardano payment address for " + name, ex);
        }
    }

    public String paymentKeyHash(CardanoCommandRunner runner) {
        return runner.run("address", "key-hash", "--payment-verification-key-file", verificationKey.toString()).trim();
    }

    /** SHA-256 over the encoded verification-key bytes, rendered as lowercase hexadecimal. */
    public String serverId() {
        try {
            JsonNode key = MAPPER.readTree(Files.readAllBytes(verificationKey));
            String cborHex = key.path("cborHex").asText();
            if (cborHex.isBlank()) throw new IllegalArgumentException("verification key has no cborHex");
            return EventCrypto.sha256Hex(HexFormat.of().parseHex(cborHex));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read verification key " + verificationKey, ex);
        }
    }

    public String paymentUtxo(CardanoCommandRunner runner, Set<String> excluded) {
        return eligibleUtxos(runner).entrySet().stream()
                .filter(entry -> !excluded.contains(entry.getKey()))
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .orElseThrow(() -> new IllegalStateException("No payment UTxO is available for " + name))
                .getKey();
    }

    public String collateralUtxo(CardanoCommandRunner runner, Set<String> excluded) {
        return eligibleUtxos(runner).entrySet().stream()
                .filter(entry -> !excluded.contains(entry.getKey()) && entry.getValue() > 0)
                .min(Comparator.comparingLong(Map.Entry::getValue))
                .orElseThrow(() -> new IllegalStateException("No collateral UTxO is available for " + name))
                .getKey();
    }

    private Map<String, Long> eligibleUtxos(CardanoCommandRunner runner) {
        String json = runner.run("query", "utxo", "--address", address,
                "--testnet-magic", runner.networkMagic(), "--output-json");
        try {
            JsonNode root = MAPPER.readTree(json);
            java.util.LinkedHashMap<String, Long> result = new java.util.LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> utxos = root.fields();
            while (utxos.hasNext()) {
                Map.Entry<String, JsonNode> entry = utxos.next();
                JsonNode value = entry.getValue().path("value");
                if (value.size() == 1 && value.path(LOVELACE).canConvertToLong()) {
                    result.put(entry.getKey(), value.path(LOVELACE).longValue());
                }
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to decode payment-address UTxOs", ex);
        }
    }
}
