# Implementation Report

## Task

Implement `specs/phase-0.5.md`: the D2 Navigation Layer / Vicinity gossip
protocol on top of Phase 0.4 SecureCyclon, deterministic topic ordering and
finger-topic computation, a bounded Navigation view, a persistent local
subscription store, the `NAVIGATION_REQUEST`/`NAVIGATION_RESPONSE` wire
protocol, node runtime integration, control-API endpoints, unit/integration
tests, three-node acceptance, and evidence. Use the `PubSub-private` repository
as the protocol reference and this repository's implementation report template.

## Outcome

Implemented the Navigation/Vicinity layer as a new `libs/navigation` module and
wired it into the existing node runtime, wire protocol, and transport without
modifying SecureCyclon or its view. Phase 0.5 is complete: the full-source
acceptance run (`scripts/acceptance/phase-0.5.sh`, invoked with Git Bash) passed
end to end, all unit and integration tests pass, and evidence is available
under `implementation-reports/evidence/phase-0.5/`.

## Completed

- Added a transport- and registry-independent `libs/navigation` module:
  `TopicOrdering` (deterministic lexicographic ordinal assignment from an
  active topic-id set), `FingerTopics` (cyclic distance and `x, x +/- b^0,
  x +/- b^1, ...` target enumeration, stopping once the distance exceeds
  `T/2`, duplicates collapsed), a bounded `NavigationView` (up to `c`
  candidates per finger-topic target, ranked exact-topic-first, then nearest
  cyclic distance, then freshest, never self, never a duplicate node id per
  target slot), and `NavigationEngine`, the transport-free Vicinity gossip
  state machine (mirrors `SecureCyclon`'s shape: `cycle`/`request`/`response`,
  `Outgoing` records, structured log emission).
- `NavigationPeerDescriptor` (`nodeId`, `host`, `port`, `subscribedTopicIds[]`,
  `freshness`) is a distinct type from the SecureCyclon `PeerDescriptor`;
  `NavigationEngine.ingestSample` accepts raw `PeerSamplingService` samples as
  low-priority, unknown-subscription candidates without reading or mutating
  SecureCyclon's own view.
- Added a persistent, newline-delimited `SubscriptionStore` (atomic
  write-then-rename), independent of the on-chain registry.
- Added `NAVIGATION_REQUEST`/`NAVIGATION_RESPONSE` to `MessageType`, a
  `NavigationExchange` payload (sender descriptor, sender subscriptions,
  selected candidates, protocol version) on `ProtocolMessage`, and matching
  `ProtocolCodec` validation. Dispatch in `PeerSessionHandler` reuses the
  existing generic sampling-message routing and dynamic-connection mechanism
  (`sendSampling`/`replySampling`), so no new transport code was required for
  connecting to a learned candidate without an active session.
- Added `NavigationRuntime` (bridges the engine to the Netty runtime and the
  Cardano registry: pulls fresh SecureCyclon samples every cycle, recomputes
  topic ordering when the active topic set changes, sends/receives framed
  `NAVIGATION_*` messages) and a `NodeTransportListener` that dispatches a
  single `PubSubTransport` listener to the peer-sampling, navigation, and
  event sub-listeners by message type.
- Added `GET /v1/navigation/view` (topic ordering, subscriptions, finger
  topics, selected peers per target) and `POST/DELETE /v1/subscriptions/{topicId}`
  plus `GET /v1/subscriptions` to `EventControlServer`.
- `RegistrySynchronizer.activeTopicIds()` exposes the active (non-tombstoned)
  topic-id set so `NavigationRuntime` can recompute topic ordering and finger
  topics whenever the registry snapshot changes, without a node restart.
  Changing the active topic set clears the Navigation view (ordinal numbers
  can refer to a different topic once the set changes) and repopulates it
  through subsequent gossip; adding/removing a *subscription* only prunes
  finger-topic targets that are no longer needed, without clearing the view.
- Added Phase 0.5 configuration (`navigation:` section: `capacity`,
  `routingBase`, `cycleIntervalMs`, `staleAfterMs`, optional
  `subscriptionsPath`) to `NodeConfig`/`NodeConfigLoader`, `ops/config/phase-0.5/`,
  `ops/infra/phase-0.5/compose.yaml`, and `scripts/acceptance/phase-0.5.sh` /
  `phase-0.5.mjs`.
- Added structured `NAVIGATION_STARTED`, `NAVIGATION_CYCLE`,
  `NAVIGATION_GOSSIP_SENT`, `NAVIGATION_GOSSIP_RECEIVED`,
  `NAVIGATION_VIEW_UPDATED`, `NAVIGATION_FINGER_TOPICS_UPDATED`, and
  `NAVIGATION_PEER_REMOVED` log events.

## Files Changed

| Area | Files and purpose |
| --- | --- |
| Modules | `settings.gradle.kts`; new `libs/navigation/build.gradle.kts`; dependency added to `libs/protocol-core/build.gradle.kts` and `apps/pubsub-node/build.gradle.kts`. |
| Navigation core | `libs/navigation/src/main/java/org/pubsub/prototype/navigation/{NavigationPeerDescriptor,NavigationExchange,TopicOrdering,FingerTopics,NavigationView,NavigationEngine,SubscriptionStore}.java`. |
| Wire protocol | `MessageType.java` (`NAVIGATION_REQUEST`/`NAVIGATION_RESPONSE`), `ProtocolMessage.java` (`navigation` field, `navigationGossip` factory), `ProtocolCodec.java` (validation) under `libs/protocol-core/src/main/java/org/pubsub/prototype/protocol/`. |
| Transport | `PeerSessionHandler.java` under `libs/transport-netty/src/main/java/org/pubsub/prototype/transport/` (dispatch new message types through the existing generic sampling path). |
| Node runtime | New `NavigationRuntime.java`, `NodeTransportListener.java`; changes to `NodeConfig.java`, `NodeConfigLoader.java`, `RegistrySynchronizer.java`, `PubSubNodeMain.java`, `EventControlServer.java` under `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/`. |
| Tests | `TopicOrderingTest.java`, `FingerTopicsTest.java`, `NavigationViewTest.java`, `NavigationEngineTest.java`, `SubscriptionStoreTest.java` (`libs/navigation`); `NavigationCodecTest.java` (`libs/protocol-core`); `NavigationIntegrationTest.java` (`apps/pubsub-node`). |
| Operations | `ops/config/phase-0.5/node-{1,2,3}.yaml`, `ops/infra/phase-0.5/compose.yaml`, `scripts/acceptance/phase-0.5.sh`, `scripts/acceptance/phase-0.5.mjs`. |
| Documentation | `README.md`, `documentation/scripts.md`, `libs/navigation/README.md`, this report. |
| Generated evidence | `implementation-reports/evidence/phase-0.5/` (ignored by Git, following the existing repository convention). |

## Validation

Validation was executed on Windows with Java 21 Gradle toolchains, Git Bash,
Docker Desktop Linux containers, the existing Cardano testnet integration, and
the real Aiken registry contract.

- `gradlew.bat build`: passed (all modules, including the new `libs:navigation`).
- Unit tests: `TopicOrderingTest`, `FingerTopicsTest`, `NavigationViewTest`,
  `NavigationEngineTest`, `SubscriptionStoreTest` cover deterministic ordering
  from an unordered/duplicate topic-id set, cyclic modulo distance, finger-topic
  generation (including the `T/2` stopping rule and multi-subscription union),
  exact-topic-beats-nearby / nearer-beats-farther / freshness-tie-break /
  bounded-capacity / no-self / no-duplicate-node-id Vicinity selection rules,
  ordering-change view invalidation vs. subscription-change target pruning, and
  subscription-store persistence across reloads.
- `NavigationCodecTest` covers `NAVIGATION_REQUEST`/`NAVIGATION_RESPONSE`
  round-tripping and rejection of a missing exchange payload.
- `NavigationIntegrationTest` runs real TCP sockets and the actual Netty/HELLO
  path: two nodes converge to knowing each other's real subscriptions and
  establish a live transport connection from a Navigation-learned candidate
  with no prior session, and `POST /v1/subscriptions` persists to disk and
  updates the engine's finger topics.
- `scripts/acceptance/phase-0.5.sh`, invoked with Git Bash, **passed** on the
  final implementation on 2026-09-06 (Europe/Athens); `final-result.txt`
  contains `Phase 0.5 acceptance passed`. The run built/tested the repository,
  reused the Phase 0.4 asymmetric bootstrap topology, tore down and recreated
  a fresh Cardano devnet, deployed the registry, created five active topics,
  subscribed node-1/node-2/node-3 to three different topics, confirmed
  SecureCyclon discovery (`SECURECYCLON_GOSSIP_RECEIVED`) and Navigation
  startup/gossip (`NAVIGATION_STARTED`, `NAVIGATION_GOSSIP_RECEIVED`) in all
  three containers, captured initial and converged Navigation views, verified
  each node's finger-topic set against an independent JavaScript
  recomputation of the deterministic topic ordering, verified every selected
  candidate carried learned (non-`null`) subscription information, added a
  second subscription to node-1 and confirmed its finger-topic set changed
  without a restart, stopped node-3 and confirmed its entries disappeared from
  node-1's and node-2's Navigation views continuously for 12+ seconds, restarted
  it with the same persistent identity and confirmed rediscovery, created a
  sixth topic and confirmed the topic ordering updated on all nodes, deleted
  one of the original topics and confirmed it left every node's active
  ordering, and finally published a Phase 0.3 signed event from node-2 and
  confirmed `EVENT_ACCEPTED`/`PONG_RECEIVED` on the other nodes after
  convergence — all logged in the acceptance run supplied for this report.
- All prior module test suites (SecureCyclon, protocol-core, transport-netty,
  registry, event-core) continue to pass unchanged.

Evidence is under `implementation-reports/evidence/phase-0.5/`:

- `node-{1,2,3}.yaml`, `bootstrap-compose.yaml`, `node-{2,3}-bootstrap.txt`
  (asymmetric seed verification).
- `active-topic-ordering-input.txt`, `devnet-health.json`,
  `funded-address-balances.txt`, `deployment.txt`.
- `subscribe-node-{1,2,3}.json`, `initial-navigation-views.json`,
  `initial-sampling-views.json`, `converged-navigation-views.json`,
  `finger-topic-verification.json`, `selection-quality.json`,
  `resubscribe-node-1.json`.
- `failure-removal-navigation-views.json`,
  `restart-rediscovery-navigation-views.json`, `topic-created-views.json`,
  `topic-deleted-views.json`.
- `published-event.json`, per-node `*-navigation-gossip.log`,
  `*-health-after-convergence.log`, `*-final.log`.
- `acceptance-output.log` and `final-result.txt`.

## Notes

- Primary source: sibling `PubSub-private` repository (`src/pubsub/VicinityNav.java`,
  `src/util/MathPower.java`, `src/vic/Vicinity.java`). Since that simulation
  gives every node instant, global knowledge of every other node's single
  topic (via a shared `TopicRegistry`), while Phase 0.5 subscriptions are
  local, private, and possibly plural per node, `FingerTopics`/`NavigationView`
  reproduce the reference's distance-then-freshness ranking and bounded-bucket
  replacement logic directly rather than its logarithmic bucket-index scheme,
  and a peer's distance is treated as unknown (worst-ranked) until its real
  subscriptions are learned through an actual gossip exchange. See
  [the module reference mapping](../libs/navigation/README.md) for the full
  mapping and adaptations. Neither reference repository was modified. Only the
  Navigation layer (`VicinityNav` equivalent) is in scope for Phase 0.5; the
  ring-based same-topic dissemination layer (`VicinityTop`/`Dissemination` in
  the reference) is out of scope, and Phase 0.3's temporary broadcast-forwarding
  path remains the event dissemination mechanism.
- A real correctness bug surfaced only under the live three-node acceptance
  run: merging a gossip-propagated candidate performed no freshness check, so
  a departed peer's entry could be pruned locally by one node's own periodic
  cycle and then re-inserted moments later by an incoming exchange from a peer
  that had not pruned its own copy yet, indefinitely delaying "stale entries
  are eventually removed." The fix rejects any propagated candidate whose
  `freshness` is already older than `staleAfterMs` at merge time (the sender's
  own descriptor is always accepted, since receiving a live message is itself
  proof of freshness). This is recorded in repository memory for future
  gossip-protocol work.
- Changing the active topic set clears the entire Navigation view rather than
  attempting to preserve entries under shifted ordinal numbers, since the same
  integer ordinal can refer to a different topic once the active set changes;
  the view is then repopulated through normal gossip cycles. This trades
  transient churn for correctness and is called out explicitly since it
  differs from the finer-grained pruning used for ordinary subscribe/unsubscribe.
- `ops/infra/phase-0.5/compose.yaml` intentionally keeps `name: phase-0.1`,
  matching the existing convention shared with `ops/infra/phase-0.4/compose.yaml`
  (both phases run under the same Compose project/network and are not meant to
  run concurrently); running the Phase 0.5 acceptance script stops and replaces
  any currently running Phase 0.4 containers under that project name.
- The acceptance script must be invoked with Git Bash
  (`C:\Program Files\Git\bin\bash.exe`), not WSL's default `bash.exe`/`wsl.exe`:
  WSL's distro has no Node.js on `PATH`, which the `.mjs` assertion helper
  requires, while Git Bash inherits the Windows `PATH` (Node.js, Docker).
- `ops/config/*/node-*.yaml` files are CRLF-terminated on disk; the acceptance
  script's asymmetric-bootstrap check now strips `\r` before an exact-line
  `grep -qx`, since CRLF silently fails whole-line matches under `set -e`.
