package org.pubsub.prototype.registry.cardano;

final class CardanoRegistryNames {
    private CardanoRegistryNames() {
    }

    enum ConfigField {
        RUNTIME_DIR("runtimeDir"),
        SIGNER("signer");

        private final String value;

        ConfigField(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    enum RuntimeKey {
        NETWORK_MAGIC("CARDANO_NETWORK_MAGIC"),
        NODE_SOCKET_PATH("CARDANO_NODE_SOCKET_PATH"),
        TOPIC_REGISTRY_VALIDATOR_ADDRESS("TOPIC_REGISTRY_VALIDATOR_ADDRESS"),
        LEGACY_REGISTRY_VALIDATOR_ADDRESS("REGISTRY_VALIDATOR_ADDRESS"),
        TOPIC_POLICY_ID("TOPIC_POLICY_ID"),
        LEGACY_TOPIC_POLICY_ID("REGISTRY_TOPIC_POLICY_ID"),
        REGISTRY_BACKEND("REGISTRY_BACKEND"),
        REGISTRY_SCRIPT_UTXO_CACHE("REGISTRY_SCRIPT_UTXO_CACHE"),
        REGISTRY_TX_ARTIFACTS_DIR("REGISTRY_TX_ARTIFACTS_DIR");

        private final String value;

        RuntimeKey(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    enum IdentityFile {
        KEYS_DIR("keys"),
        PAYMENT_ADDRESS("payment.addr"),
        PAYMENT_SIGNING_KEY("payment.skey"),
        PAYMENT_VERIFICATION_KEY("payment.vkey");

        private final String value;

        IdentityFile(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    enum Asset {
        LOVELACE("lovelace");

        private final String value;

        Asset(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    enum StoreField {
        VALIDATOR_ADDRESS("validatorAddress"),
        POLICY_ID("policyId"),
        NEXT_SEED("nextSeed"),
        TOPICS("topics");

        private final String value;

        StoreField(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    enum ScriptDataField {
        CONSTRUCTOR("constructor"),
        FIELDS("fields"),
        BYTES("bytes"),
        INT("int"),
        LIST("list");

        private final String value;

        ScriptDataField(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    enum UtxoField {
        VALUE("value"),
        INLINE_DATUM("inlineDatum"),
        INLINE_DATUM_JSON("inlineDatumJson");

        private final String value;

        UtxoField(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }
}
