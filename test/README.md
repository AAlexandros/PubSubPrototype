# System tests

This folder contains repository tests that run scripts or services against a
local Cardano devnet and Docker Compose. Routine operational commands remain in
`scripts/`; reusable test assertions live in `helpers/`. Scenario
node configs and Compose files live in `fixtures/`. Java unit and integration
test sources are under `java/`; Gradle maps the three projects' test source
sets there. Test-only Node helpers live in `lib/`. The `three-node` fixture is
shared by peer connectivity, topic registry policy, and event authorization;
the other fixtures are named after their acceptance scenarios.

## Integrated local testbed

The `testbed/` scripts run 3-50 Pub/Sub nodes, three replication servers, and
the Docker Cardano devnet. Settings are in `testbed/settings.yaml`. From the
repository root:

```bash
./test/testbed/up.sh --nodes 3
./test/testbed/status.sh
./test/testbed/logs.sh
./test/testbed/down.sh
```

The first start builds application distributions and container images,
initializes and funds the devnet, deploys both registries, and registers the
replication servers. Generated Compose and application configs live under
`.tools/testbed/`. Node logs are under `.tools/testbed/logs/node-<n>/` as
`node.log`, `initialization.log`, and `network.log`. Use
`./test/testbed/collect-logs.sh` to copy a diagnostic bundle.

The testbed exposes node command ports at `127.0.0.1:8001...`, replication
server ports at `127.0.0.1:8101...`, and peer transport ports at `7001...`.
Use `node scripts/pubsub-node.mjs status --node 1` for the first node. The
[node CLI guide](../documentation/node-cli.md) lists other commands.

`./test/testbed/reset.sh` discards generated testbed and devnet state and
Docker volumes. The `devnet/` directory has direct `init.sh`, `start.sh`,
`wait-healthy.sh`, `fund-identities.sh`, `status.sh`, `stop.sh`, and `reset.sh`
commands for the local Cardano chain. `testbed/` invokes them as needed.

## Run everything

From the repository root, use Git Bash or WSL:

```bash
bash test/run-all.sh
```

To resume after an acceptance failure, pass the first scenario to rerun, for
example `bash test/run-all.sh topic-registry-policy.sh`. Gradle and Node utility
tests still run before the selected acceptance scenario.

This runs the Gradle test suite, Node utility tests, then each acceptance test
below in order. Docker Desktop's Linux engine, Node.js, Gradle dependencies,
and the repository's Cardano tooling are required.

Each acceptance scenario uses its own Compose project and removes that
project's containers and volumes before it starts. Acceptance tests also reset
local devnet state and may remove or recreate identities and generated runtime
files. Run the full suite when the local testbed data can be discarded. Logs and
machine-readable evidence are kept locally under `.tools/test-results/` and are
ignored by Git.

To run one acceptance scenario, invoke it directly, for example:

```bash
bash test/acceptance/topic-navigation.sh
```

## Coverage

| Test | Distinct behavior checked |
| --- | --- |
| `peer-connectivity.sh` | Three-node peer mesh, ping, disconnect detection, and reconnect with stable identity. |
| `topic-registry-policy.sh` | Topic creation, administration, authorization, policy changes, tombstones, and registry cache rebuild. |
| `event-authorization-and-delivery.sh` | Signed event delivery, sequence continuity, duplicate rejection, unauthorized publisher rejection, invalid signatures, and inactive topics. |
| `secure-peer-sampling.sh` | SecureCyclon view invariants, convergence, failure removal, restart, and rejection of an unauthenticated peer handshake. |
| `topic-navigation.sh` | Topic-aware navigation, topic membership changes, resubscription, convergence, and peer failure recovery. |
| `topic-dissemination-repair.sh` | Dissemination candidates, event delivery and storage through one registered server, repair after peer failure, rejoin, and a second topic. |
| `registry-replication-recovery.sh` | Live topic and replication registry observations, freshness/outage behavior, replica failure and repair, offline subscriber recovery, and scaling. |
| `subscriber-local-catch-up.sh` | Subscriber-local event history, restart persistence, automatic catch-up, exact event lookup, and contiguous recovery cursor. |

The older standalone replica persistence and repair phase scripts were removed as
separate entries because their assertions are exercised by
`registry-replication-recovery.sh`; shared assertion code remains under
`helpers/`. The eight scenarios above cover the independent system behaviors
without keeping phase-numbered entrypoints.

The registry policy scenario also uses `helpers/registry/` to derive local
devnet payment hashes and build an intentionally invalid last-owner removal
transaction for validator rejection.
