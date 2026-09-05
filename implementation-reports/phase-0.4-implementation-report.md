# Implementation Report

## Task

Implement `specs/phase-0.4.md`: the SecureCyclon peer-sampling layer, runtime
peer discovery, asymmetric bootstrap, protocol/transport tests, three-node
acceptance, and evidence. Use the provided PeerNet simulation as the protocol
reference and this repository's implementation report template.

## Outcome

Implemented SecureCyclon peer sampling and integrated it with the existing node,
framed Netty transport, persistent identities, health checks, and event path.
The three nodes discover peers through gossip from an asymmetric seed topology.
Phase 0.4 is complete: the final-source acceptance run passed, and all 38 tests
pass. Evidence is available under `implementation-reports/evidence/phase-0.4/`.

## Completed

- Added a Cardano- and transport-independent `PeerSamplingService` exposing
  immutable view snapshots and optional random peer samples.
- Added validated peer descriptors with persistent node ID, advertised listening
  host, and port. HELLO/HELLO_ACK carry the advertised endpoint.
- Ported the reference's oldest-link redemption, timestamp ordering, shuffled
  exchange selection, fresh self-link insertion, ownership transfer, observation
  samples, pending-link locking, replacement priorities, and retained
  non-swappable links. View capacity, swap length, cycle interval, history age
  threshold, and test seed are configurable.
- Enforced self/duplicate/structure/ownership/link-ID validation before adoption.
  Added fresh-link frequency enforcement, replay rejection, ownership-fork
  detection, offender removal, and evidence-bearing reports. Malformed exchanges
  produce rejection logs; proof-backed offender removal is an intentional
  defender action, not adoption of invalid links.
- Added separate `SECURECYCLON_REQUEST`, `SECURECYCLON_RESPONSE`, and
  `SECURECYCLON_REPORT` messages using the existing framing/version checks.
- Added on-demand connections for learned descriptors, handshake identity
  matching, bounded connection/handshake waits, pending message delivery, and
  duplicate-connection selection. Seed reconnections do not repopulate the view.
- Integrated the scheduler and read-only `/v1/peer-sampling/view` endpoint.
  SecureCyclon remains independent of registry, event validation, topic
  navigation, and dissemination logic.
- Added Phase 0.4 configuration: node-1 has no seeds; node-2 and node-3 know only
  node-1. View size and swap length are 2; cycle interval is 2 seconds.
- Added deterministic protocol fixtures, real-socket integration tests, and
  the complete Cardano/devnet acceptance flow with snapshots and logs.

## Files Changed

| Area | Files and purpose |
| --- | --- |
| Modules | `settings.gradle.kts`; new `libs/peer-sampling-api/build.gradle.kts` and `libs/securecyclon/build.gradle.kts`; dependencies in `libs/protocol-core/build.gradle.kts` and `apps/pubsub-node/build.gradle.kts`. |
| Sampling API | `libs/peer-sampling-api/src/main/java/org/pubsub/prototype/sampling/PeerDescriptor.java`, `PeerSamplingService.java`. |
| Protocol implementation | `libs/securecyclon/src/main/java/org/pubsub/prototype/securecyclon/SecureCyclon.java`, `SecureLink.java`, `Exchange.java`, `LinkProof.java`. |
| Wire protocol | `MessageType.java`, `ProtocolMessage.java`, `ProtocolCodec.java` under `libs/protocol-core/src/main/java/org/pubsub/prototype/protocol/`. |
| Transport | `PubSubTransport.java`, `PeerSessionHandler.java`, `TransportListener.java` under `libs/transport-netty/src/main/java/org/pubsub/prototype/transport/`. |
| Node runtime | New `PeerSamplingRuntime.java`; changes to `NodeConfig.java`, `NodeConfigLoader.java`, `PubSubNodeMain.java`, and `EventControlServer.java` under `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/`. |
| Tests | `SecureCyclonTest.java`, `peernet-non-tit-for-tat.properties`, `SecureCyclonCodecTest.java`, `PeerSamplingIntegrationTest.java`. |
| Operations | `ops/config/phase-0.4/node-{1,2,3}.yaml`, `ops/infra/phase-0.4/compose.yaml`, `scripts/acceptance/phase-0.4.sh`, `scripts/acceptance/phase-0.4.mjs`. |
| Documentation | `README.md`, `documentation/scripts.md`, `libs/securecyclon/README.md`, this report. |
| Generated evidence | `implementation-reports/evidence/phase-0.4/` (ignored by Git, following the existing repository convention). |

## Validation

Validation was executed on Windows with Java 21 Gradle toolchains, Git Bash,
Docker Desktop Linux containers, the existing Cardano testnet integration, and
the real Aiken registry contract.

- `gradlew.bat test :apps:pubsub-node:installDist`: passed.
- Final aggregate: 38 tests, zero failures/errors/skips (11 SecureCyclon protocol,
  2 SecureCyclon codec, 2 sampling runtime integration, and 23 existing tests).
  Additional history-expiry and pending-link-locking tests passed via
  `gradlew.bat :libs:securecyclon:test`.
- Bash/Node syntax checks and `git diff --check`: passed.
- `scripts/acceptance/phase-0.4.sh`, invoked with Git Bash: passed on the final
  implementation on 2026-09-06 (Europe/Athens), exit code 0.
  `final-result.txt` contains `Phase 0.4 acceptance passed`.
- Protocol tests cover oldest-peer selection, exchange construction, exact
  deterministic fixture results, retained and returned link ownership,
  duplicate/self/malformed rejection, view bounds, fresh-link frequency/replay,
  ownership evidence and reports, seed non-pinning, and asymmetric
  discovery/failure/restart.
- Integration tests use real TCP sockets and the actual HELLO/gossip transport:
  learned endpoints establish connections, simultaneous connection attempts
  converge, malformed frames/exchanges preserve valid state, and persistent
  identities rediscover after restart. Existing transport, event, identity,
  and registry tests also pass.
- Acceptance builds/tests the repository, creates/funds the devnet, deploys the
  registry, verifies asymmetric configuration and gossip readiness, captures
  bounded views, observes node-2/node-3 discovery, stops node-3, requires its
  absence from both remaining views continuously for 12 seconds, restarts it
  with the same identity, verifies rediscovery, and submits an invalid exchange
  over a real framed socket after HELLO. It then creates an on-chain open topic,
  publishes a signed event from node-2, verifies acceptance on nodes 1 and 3,
  and captures fresh PONG logs after convergence.

Evidence is under `implementation-reports/evidence/phase-0.4/`:

- `bootstrap-compose.yaml`, copied node YAML files, and leaf bootstrap extracts.
- `initial-views.json`, `converged-views.json`, per-node discovery/gossip logs.
- `failure-removal-views.json`, `restart-rediscovery-views.json`.
- `invalid-exchange.json`, before/after snapshots, `invalid-exchange-rejection.log`.
- `devnet-health.json`, funding/deployment records, `event-topic-id.txt`,
  `published-event.json`, and per-node health/final logs.
- `acceptance-output.log` and `final-result.txt`.
- `test-summary.json`, JUnit XML under `test-results/`, `reference.json`, and
  the copied deterministic conformance fixture.

## Notes

- Primary source: sibling `cyclon` repository, commit
  `0f3a9510fb7dfcbf961b61169363988bb4ab453c`. The implementation follows
  `AbstractCyclonBehavior`, `CyclonPeer`, `AbstractBlockerBehavior`, and
  `StructureUtils`. The checked-in configuration disables tit-for-tat.
  [The module reference mapping](../libs/securecyclon/README.md) explains each
  mapping and adaptation. Neither reference repository was modified.
- Phase 0.4 requires unique node IDs, unlike the simulation's allowance for
  multiple links to a node. Replacement preserves one descriptor per node and
  restores swap permission when legitimate ownership returns. The reference's
  implemented but disabled frequency detector is enabled to meet this phase's
  fresh-link protection requirement.
- The deterministic fixture is a hand-traced reference case with a supplied
  shuffle seed; it is not represented as output from an executed PeerNet oracle.
- Link age is creation-time ordering. The reference expires observation history;
  failed view entries are removed by redemption/replacement rather than by
  refreshing ages from PONG or imposing a different random-peer protocol.
- Ownership evidence and HELLO retain the simulation/existing runtime's trust
  assumptions. This phase does not add cryptographic ownership signatures or
  authenticated HELLO proofs. Persistent Ed25519 identities and Phase 0.3 event
  signatures remain in place.
- The first devnet launch encountered stale generated genesis files. Acceptance
  now stops the test containers and archives generated runtime/keys/state under
  `.tools/phase-0.4-devnet-backups/` before initializing a fresh devnet. Private
  keys are not copied into evidence. Pub/Sub identity volumes are retained.
- Initial snapshots are taken after node readiness; gossip may already have
  started. The bootstrap configuration and startup logs establish the initial
  asymmetric seed topology independently of snapshot timing.
- Phase 0.3 forwarding remains a temporary baseline over established sessions,
  not the D2 dissemination layer. Test containers remain running for inspection.
