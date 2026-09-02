# Implementation Report

## Task

Continue Phase 0.2 with `specs/phase-0.2.1.md`: replace the prototype Topic Registry mutation path with a Cardano-backed registry that uses Aiken scripts for topic identity and state-transition enforcement, while preserving the existing Java API and node observation flow.

## Outcome

Phase 0.2.1 is implemented and the Phase 0.2 acceptance flow passed end to end.

The registry now creates topic identity by minting one unique topic token under the Aiken topic policy from a unique creation UTxO. Topic mutations are submitted as Cardano transactions that spend and recreate the topic script UTxO with an updated inline datum. Pub/Sub nodes observe the registry from Cardano script UTxOs through a read-only cache when they cannot run `cardano-cli` directly inside their containers.

The file-backed registry fallback has been removed. Registry reads and mutations now require the Cardano registry path, with only the script UTxO cache used for read recovery.

## Completed

- Implemented the Aiken topic-state spending validator.
- Implemented the Aiken topic minting policy.
- Enforced the D2 authorization matrix in the validator:
  - owner-only for `deleteTopic`, `addOwner`, `removeOwner`, `addAdmin`, and `removeAdmin`
  - owner-or-admin for `addPublisher`, `removePublisher`, `setReplicationFactor`, and `setRetentionPeriod`
- Enforced immutable `topicId` across state transitions.
- Enforced positive `replicationFactor` and `retentionPeriod`.
- Enforced last-owner protection through validator state invariants.
- Enforced irreversible deletion by allowing only active-to-deleted transitions and no deleted-state resurrection.
- Required the state UTxO to preserve exactly one topic token for non-delete mutations.
- Required create-topic minting to mint exactly one token whose token name is derived from the unique creation UTxO.
- Added Java script-data encoding/decoding for Aiken inline datums and redeemers.
- Added real Cardano transaction construction for:
  - `createTopic`
  - `deleteTopic`
  - owner mutations
  - admin mutations
  - publisher mutations
  - `setReplicationFactor`
  - `setRetentionPeriod`
- Added collateral UTxO selection for Plutus transactions.
- Added Docker-backed `cardano-cli latest` execution for the local devnet.
- Added registry transaction logging for submitted Cardano transactions.
- Added bounded process execution and bounded Docker log polling to avoid stalled acceptance runs.
- Removed the file-backed registry fallback from the production registry adapter.
- Updated node polling so nodes observe Cardano script UTxOs through the registry UTxO cache.
- Added malicious/direct transaction coverage for an unauthorized `node-3` script-spend attempt.
- Added malicious/direct transaction coverage for a zero-owner output datum that bypasses the Java API and is expected to fail at the Aiken validator.

## Evidence

Evidence is stored under:

```text
implementation-reports/evidence/phase-0.2/
```

Key artifacts:

- `acceptance-output.log`
- `final-result.txt`
- `registry-transactions.log`
- `final-registry-snapshot-with-tombstones.json`
- `rejected-unauthorized-transaction.txt`
- `rejected-last-owner-removal.txt`
- `rejected-direct-last-owner-removal.txt`
- `pubsub-node-1.log`
- `pubsub-node-2.log`
- `pubsub-node-3.log`

The acceptance log records the full flow:

- Create `orders`.
- Observe `orders` on all three nodes.
- Add admin `node-2`.
- Add publisher `node-3`.
- Set replication factor to `3`.
- Set retention period to `7200`.
- Reject unauthorized `node-3` owner mutation.
- Reject last-owner removal.
- Reject direct last-owner removal with a zero-owner datum at the validator boundary.
- Delete `orders`.
- Create `payments`.
- Tear down containers and devnet.
- Finish with `Phase 0.2 acceptance passed`.

`final-result.txt` contains:

```text
Phase 0.2 acceptance passed
```

`registry-transactions.log` contains submitted transaction hashes for first topic creation, all successful mutations, deletion, and second topic creation.

`rejected-unauthorized-transaction.txt` shows a real `cardano-cli latest transaction build` failure for a PlutusV3 spending script with signer `node-3`; this confirms the unauthorized mutation is rejected by the Aiken validator path rather than only by Java preflight checks.

`rejected-direct-last-owner-removal.txt` shows a direct `cardano-cli latest transaction build` attempt that spends the topic script UTxO with a `removeOwner` redeemer and an output datum containing zero owners. The captured result is a PlutusV3 spending script failure from the Aiken validator.

## Validation

Passed:

```powershell
$env:GRADLE_USER_HOME='C:\Users\AAntonov\Repositories\aueb\PubSubPrototype\.gradle-user-home'; .\gradlew.bat :libs:registry-cardano:test
```

Passed:

```powershell
& "C:\Program Files\Git\bin\bash.exe" ./scripts/registry/build.sh
```

Passed end to end, with evidence captured in `implementation-reports/evidence/phase-0.2/acceptance-output.log`:

```powershell
& "C:\Program Files\Git\bin\bash.exe" ./scripts/acceptance/phase-0.2.sh
```

## Files Changed

- `contracts/topic-registry/validators/topic_registry.ak`
- `scripts/registry/build.sh`
- `scripts/registry/build-aiken-wsl.sh`
- `scripts/registry/common.sh`
- `scripts/registry/deploy.sh`
- `scripts/registry/direct-last-owner-removal.sh`
- `scripts/registry/export-aiken-scripts.mjs`
- `scripts/registry/query.sh`
- `scripts/acceptance/phase-0.2.sh`
- `ops/infra/devnet/scripts/common.sh`
- `ops/infra/devnet/scripts/fund-identities.sh`
- `ops/infra/devnet/compose.yaml`
- `libs/registry-api/src/main/java/org/pubsub/prototype/registry/api/*`
- `libs/registry-cardano/src/main/java/org/pubsub/prototype/registry/cardano/*`
- `libs/registry-cardano/src/test/java/org/pubsub/prototype/registry/cardano/*`
- `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/*`

## Notes

- The acceptance run proves real on-chain create, update, delete, second-create, node observation, unauthorized direct transaction rejection, and direct zero-owner datum rejection.
- The Java API still rejects last-owner removal before transaction construction. The direct malformed transaction case exists specifically to prove the same invariant at the validator boundary.
- The registry adapter no longer includes a file-backed development/testing backend.
