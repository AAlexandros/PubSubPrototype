# Cardano CLI command reference

This page documents the `cardano-cli` commands used to administer the Cardano
registries. Most operators should run the scripts listed below rather than assembling
these commands manually: the scripts supply the network magic, node socket,
keys, UTxOs, datums, redeemers, and generated file paths.

## How commands are executed

Registry operator scripts default to the `HOST` Java backend, which invokes
the installed `cardano-cli` using `CARDANO_NODE_SOCKET_PATH`. Set that value
and `CARDANO_NETWORK_MAGIC` in the operator process, or put both in
`runtime/cardano/network.env`. Application processes use the same host backend
with a readable Cardano node socket and the registry `deployment.env` files.
Registry views remain in memory; unavailable or invalid queries do not fall
back to a disk snapshot. See [deployment](deployment.md) for the required
layout. The Docker devnet backend and its lifecycle commands live in
[test/](../test/README.md).

## Topic Registry commands

The Java Topic Registry constructs these commands through
`CardanoCommandRunner`. Operators normally use `scripts/registry/*.sh`; the
Java layer fills in all placeholders shown below.

### Deployment and signer identity

| Command | Purpose |
| --- | --- |
| `cardano-cli address build --payment-script-file <topic-state.plutus.json> --testnet-magic <magic>` | Derives the Topic Registry validator address. |
| `cardano-cli latest transaction policyid --script-file <topic-policy.plutus.json>` | Derives the topic minting-policy ID. |
| `cardano-cli address key-hash --payment-verification-key-file <payment.vkey>` | Derives the configured transaction signer's payment-key hash. Callers supply owner or administrator hashes as command arguments. |

### UTxO queries

```text
cardano-cli query utxo \
  --address <identity-or-validator-address> \
  --testnet-magic <magic> \
  --output-json
```

For an identity address, this selects payment and collateral inputs. For the
validator address, it reads current topic datums and locates the topic UTxO to
spend. A successful query with no UTxOs returns an empty JSON object.

### Create a topic

Creation consumes an ordinary UTxO, mints one unique topic token, and locks it
with the initial datum at the Topic Registry validator:

```text
cardano-cli latest transaction build \
  --testnet-magic <magic> \
  --change-address <signer-address> \
  --tx-in <creation-utxo> \
  --tx-in-collateral <collateral-utxo> \
  --tx-out <validator-address+2000000+1 policy-id.token-name> \
  --tx-out-inline-datum-file <topic.datum.json> \
  --mint <1 policy-id.token-name> \
  --mint-script-file <topic-policy.plutus.json> \
  --mint-redeemer-file <create-topic.redeemer.json> \
  --required-signer-hash <signer-key-hash> \
  --out-file <create.txbody>
```

### Update or delete a topic

Every topic mutation uses the same transaction shape. The generated datum and
redeemer identify whether the operation adds/removes an owner, administrator,
or publisher; changes replication or retention; or creates the irreversible
inactive tombstone used for deletion.

```text
cardano-cli latest transaction build \
  --testnet-magic <magic> \
  --change-address <signer-address> \
  --tx-in <topic-script-utxo> \
  --tx-in-script-file <topic-state.plutus.json> \
  --tx-in-inline-datum-present \
  --tx-in-redeemer-file <mutation.redeemer.json> \
  --tx-in <payment-utxo> \
  --tx-in-collateral <collateral-utxo> \
  --tx-out <validator-address+2000000+1 policy-id.topic-token> \
  --tx-out-inline-datum-file <next-topic.datum.json> \
  --required-signer-hash <signer-key-hash> \
  --out-file <mutation.txbody>
```

### Sign and submit topic transactions

Valid create, update, and delete transactions finish with:

```text
cardano-cli latest transaction sign \
  --tx-body-file <transaction.txbody> \
  --signing-key-file <signer-payment.skey> \
  --testnet-magic <magic> \
  --out-file <transaction.tx>

cardano-cli latest transaction submit \
  --tx-file <transaction.tx> \
  --testnet-magic <magic>
```

The operator-facing scripts and their argument contracts are listed in the
[complete script reference](scripts.md).

## Replication Registry commands

These commands are assembled by the Replication Registry Java CLI, which the
scripts in `scripts/replication/` invoke.

### Deployment, identity, and query

| Command | Purpose |
| --- | --- |
| `cardano-cli address build --payment-script-file <replication-registry.plutus.json> --testnet-magic <magic>` | Derives the Replication Registry validator address. |
| `cardano-cli address key-hash --payment-verification-key-file <operator.vkey>` | Derives the controlling operator hash used by the datum and required signer. |
| `cardano-cli query utxo --address <validator-address> --testnet-magic <magic> --output-json` | Reads registration UTxOs before rebuilding the materialized server snapshot. |
| `cardano-cli query utxo --address <operator-address> --testnet-magic <magic>` | Selects operator payment and collateral inputs. |

### Register a new server

```text
cardano-cli latest transaction build \
  --testnet-magic <magic> \
  --change-address <operator-address> \
  --tx-in <payment-utxo> \
  --tx-out <validator-address+2000000> \
  --tx-out-inline-datum-file <registration.datum.json> \
  --required-signer-hash <operator-key-hash> \
  --out-file <registration.txbody>
```

### Update or unregister a server

Updating a registration and writing an inactive unregistration tombstone use
the same script-spend shape; their generated datum and redeemer differ:

```text
cardano-cli latest transaction build \
  --testnet-magic <magic> \
  --change-address <operator-address> \
  --tx-in <registration-script-utxo> \
  --tx-in-script-file <replication-registry.plutus.json> \
  --tx-in-inline-datum-present \
  --tx-in-redeemer-file <update-or-unregister.redeemer.json> \
  --tx-in <payment-utxo> \
  --tx-in-collateral <collateral-utxo> \
  --tx-out <validator-address+2000000> \
  --tx-out-inline-datum-file <next-registration.datum.json> \
  --required-signer-hash <operator-key-hash> \
  --out-file <registration.txbody>
```

### Sign and submit replication transactions

New registrations, updates, and unregistrations finish with:

```text
cardano-cli latest transaction sign \
  --tx-body-file <transaction.txbody> \
  --signing-key-file <operator-payment.skey> \
  --testnet-magic <magic> \
  --out-file <transaction.tx>

cardano-cli latest transaction submit \
  --tx-file <transaction.tx> \
  --testnet-magic <magic>
```

Use the repository entrypoints rather than these expanded forms:

```bash
./scripts/replication/deploy.sh
./scripts/replication/register-server.sh <replication-server-config>
./scripts/replication/unregister-server.sh <replication-server-config>
./scripts/replication/query-servers.sh
```
