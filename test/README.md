# System tests

This folder contains repository tests that run scripts or services against a
local Cardano devnet and Docker Compose. Routine operational commands remain in
`scripts/` and `ops/`; reusable test assertions live in `helpers/`.

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
