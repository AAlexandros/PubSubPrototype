# Phase 0.10 registry observation

Every Pub/Sub node and replication server independently queries the Topic and
Replication Registry UTxOs through `cardano-cli`. The configured Cardano node
and its socket are trusted. The Java processes do not verify the chain's
cryptographic consensus. Registry administration tools continue to submit
signed transactions; subscriptions remain local overlay state.

The application uses `HOST` query mode with `CARDANO_NETWORK_MAGIC`,
`CARDANO_NODE_SOCKET_PATH`, and read-only `deployment.env` files for both
registries. The generated testbed mounts those metadata files and the
read-only socket for registry reads; it does not mount a shared registry JSON
snapshot. The replication server still receives its existing Cardano payment
key for HTTP request signing pending the key-boundary decision. A
confirmed `{"...": ...}` query is validated before replacing the complete
in-memory view. `{}` is a valid empty registry. Query failure, malformed
UTxOs, duplicate identities, unexpected addresses, and mismatched topic policy
tokens leave the previous valid view intact.

The testbed settings in `ops/config/phase-0.9/testbed.yaml` are:

| Setting | Value | Effect |
| --- | ---: | --- |
| `registry.pollIntervalMs` | 5000 | Each instance polls each registry immediately at startup and then once per interval. |
| `CARDANO_CLI_TIMEOUT_MS` | 15000 | A CLI query can take at most 15 seconds before it fails. |
| `registry.maximumAgeMs` | 30000 | Authorization and membership decisions fail closed once the last valid observation exceeds 30 seconds. |

After a successful poll, ordinary registry changes should be visible within a
poll interval plus query duration. This is an observation bound after chain
confirmation, not a transaction confirmation bound. During a query outage,
the last valid view remains usable until the maximum age, after which new
registry-dependent work fails. `/v1/ops/registry` reports each registry's
`NOT_LOADED`, `FRESH`, or `STALE` state, observation age, and last valid values
for operations diagnostics. Both applications use the shared `RegistryDiagnostics`
helper to assemble that response. The last valid values may be stale; event
authorization and placement use the fresh registry views instead.
`REGISTRY_OBSERVED` logs contain registry, instance, result category, age,
duration, and chain position when available. The current CLI UTxO query does
not return a chain position, so logs mark it unavailable.

The acceptance runner is `scripts/acceptance/phase-0.10.sh`. It starts a fresh
three-node/three-server devnet, exercises topic and membership changes, tests
startup and expiry during a Cardano outage, and records a six-node query-load
sample in `implementation-reports/evidence/phase-0.10/`.
