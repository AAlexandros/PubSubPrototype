# Implementation Report

## Task

Implement `specs/phase-0.6.md`: the D2 Hybrid Dissemination Layer on top of
Phase 0.5 Navigation, including per-topic predecessor/successor ring links,
random same-topic links, vicinity gossip, targeted event forwarding,
subscription/failure/restart lifecycle handling, control APIs, structured
logging, tests, three-node acceptance, and evidence.

## Outcome

Implemented Phase 0.6 as a new `libs/dissemination` module and integrated it
with Navigation, the framed protocol, dynamic Netty connections, the node
runtime, subscriptions, registry lifecycle, configuration, and control API.
The Phase 0.3 transport-wide forwarding path is no longer used by nodes with
dissemination enabled: validated events are sent only to peers selected from
the event topic's Hybrid Dissemination view.

The full `scripts/acceptance/phase-0.6.sh` run supplied by the user passed on
2026-09-06 (Europe/Athens), including the strengthened assertion that every
ring and random target belongs to the current live participant set. Its
evidence is stored under `implementation-reports/evidence/phase-0.6/`, and the
current source also passes the clean automated test suite.

## Completed

- Added a transport-independent `DisseminationEngine` with one isolated state
  per subscribed topic, cyclic unsigned 256-bit `NodeId` predecessor/successor
  selection (including wraparound and the one-peer dual-role case), no-self
  and node-id de-duplication invariants, and configurable deterministic random
  links (default count `1`).
- Added vicinity dissemination gossip. Each cycle refreshes from Navigation's
  public same-topic candidate API, prefers the closest current dissemination
  neighbor as partner, and exchanges the closest lower/higher candidates for
  that partner. Received descriptors are topic-checked, merged, freshness
  checked, and used to recompute the ring.
- Added `NavigationEngine.peersForTopic(String)` as the sole Navigation-facing
  candidate API used by dissemination; the new layer never reads Navigation
  implementation internals or mutates Navigation/SecureCyclon views.
- Added `DISSEMINATION_REQUEST` and `DISSEMINATION_RESPONSE`, a versioned
  `DisseminationExchange` payload carrying topic, sender descriptor, and
  same-topic candidates, protocol-codec validation, framed Netty dispatch,
  and reuse of dynamic peer connections.
- Added targeted transport event sending with a per-peer FIFO connection
  queue. Local events use predecessor + successor + random links; ring-sourced
  events use the opposite ring direction + random links; random/unknown-sourced
  events use both ring directions; all paths exclude and de-duplicate the
  immediate source.
- Preserved the existing topic, event-id, signature, publisher authorization,
  duplicate, and sequence validation before dissemination. Local and remote
  first acceptance is logged once; duplicate suppression remains authoritative.
  The explicit acceptance-only `forceBroadcast` path remains available for
  malformed-event receiver tests and older configurations retain their prior
  behavior when dissemination is not configured.
- Added `DisseminationRuntime` for subscription seeding, periodic gossip,
  active-topic cleanup, targeted sends, transport failure notification, and
  same-identity restart. Disconnected peers are quarantined from stale indirect
  Navigation refreshes; direct gossip or a post-disconnect descriptor freshly
  observed by another dissemination peer proves liveness and enables rejoin.
- Added `GET /v1/dissemination/view` and
  `GET /v1/dissemination/view/{topicId}`. Subscribe/unsubscribe operations now
  create/remove dissemination views immediately; registry-deleted topics are
  removed during runtime reconciliation.
- Added all requested structured dissemination events, including lifecycle,
  cycle, gossip, view/role changes, peer removal, and `EVENT_DISSEMINATED`.
- Added Phase 0.6 node configuration (`randomLinkCount`, `cycleIntervalMs`,
  `staleAfterMs`, `randomSeed`), Docker Compose topology, acceptance assertion
  helper, evidence capture, and documentation.

## Files Changed

| Area | Files and purpose |
| --- | --- |
| Module/build | `settings.gradle.kts`; `libs/dissemination/build.gradle.kts`; dissemination dependencies in `libs/protocol-core` and `apps/pubsub-node`. |
| Dissemination core | `libs/dissemination/src/main/java/org/pubsub/prototype/dissemination/{DisseminationEngine,DisseminationExchange,DisseminationPeerDescriptor,TopicDisseminationView}.java`; module `README.md`. |
| Navigation | `NavigationEngine.java` public same-topic candidate API and its test. |
| Wire/transport | `MessageType.java`, `ProtocolMessage.java`, `ProtocolCodec.java`, `PeerSessionHandler.java`, and `PubSubTransport.java` for versioned gossip and targeted/dynamic event delivery. |
| Node runtime/API | New `DisseminationRuntime.java`; updates to `PubSubNodeMain.java`, `NodeTransportListener.java`, `NodeEventService.java`, `EventControlServer.java`, `NodeConfig.java`, and `NodeConfigLoader.java`. |
| Tests | `DisseminationEngineTest.java`, `DisseminationCodecTest.java`, `DisseminationIntegrationTest.java`; extended Navigation and Netty transport tests. |
| Operations | `ops/config/phase-0.6/node-{1,2,3}.yaml`, `ops/infra/phase-0.6/compose.yaml`, `scripts/acceptance/phase-0.6.sh`, and `phase-0.6.mjs`. |
| Evidence | `implementation-reports/evidence/phase-0.6/` with topology, subscription, forwarding, exact-once, failure/rejoin, isolation, health, and final-result artifacts. |
| Documentation | `README.md`, `documentation/scripts.md`, and this report. |

## Validation

- `gradlew.bat -g .gradle-user-home test --no-daemon`: passed. The suite covers
  ring selection and wraparound, one-peer and single-node overlays, no-self and
  duplicate handling, received/Navigation candidate improvement, rejection of
  unrelated-topic candidates, deterministic bounded random links, all event
  forwarding source roles, source exclusion, independent topics, unsubscribe,
  peer failure quarantine and rejoin, wire round trips/rejection, real-socket
  Navigation-to-dissemination convergence, and targeted dynamic transport sends
  that do not reach an unrelated active peer.
- `git diff --check`: passed (only repository line-ending conversion notices).
- The supplied `scripts/acceptance/phase-0.6.sh` result: **passed**. It built and
  tested all modules, created a fresh Cardano devnet, funded identities,
  built/deployed the real Aiken registry, created two open topics, started three
  nodes with SecureCyclon and Navigation, subscribed all nodes to the first
  topic, captured same-topic Navigation candidates, and verified the exact
  cyclic ring and one configured live random link on every node.
- Acceptance published a signed event and verified all three subscribers
  accepted its `eventId` exactly once through `EVENT_DISSEMINATED` targeted
  forwarding. It stopped node-3, verified the two remaining nodes repaired to
  a one-peer ring, published again with exact-once delivery, restarted node-3
  with its persistent identity, and verified full ring rejoin.
- Acceptance subscribed only node-1 and node-2 to the second topic, verified an
  independent two-node ring and a `404` view on node-3, published a second-topic
  event, verified node-2 accepted it exactly once and node-3 did not accept it,
  then verified `SECURECYCLON_CYCLE`, `NAVIGATION_CYCLE`, and `PONG_RECEIVED`
  remained active on all nodes.
- Evidence includes topic IDs/subscriptions, node ordering, initial/converged
  views, explicit predecessor/successor verification, random links, event logs
  and exact-once counts, failure repair, restart/rejoin, second-topic isolation,
  lower-layer health logs, full acceptance output, and `final-result.txt` with
  `Phase 0.6 acceptance passed`.
- An earlier acceptance run exposed a transient stopped-peer random link. The
  engine now filters unavailable peers from random selection,
  `DisseminationEngineTest` asserts that invariant, and the acceptance helper
  rejects any ring or random target outside the live participant set. The
  subsequent full run supplied by the user passed these strengthened checks.

## Notes

- The ring compares fixed-width lowercase `NodeId` hex strings. Because every
  ID is exactly 256 bits, lexicographic comparison is equivalent to unsigned
  numeric comparison; predecessor/successor selection explicitly falls back to
  the maximum/minimum ID for cyclic wraparound.
- Ring and random roles are separate state. In a three-node topic there are
  only two remote subscribers, so the configured random peer may also hold a
  ring role; forwarding de-duplicates physical node IDs before sending.
- Live acceptance exposed two distributed-systems edge cases that are now
  covered: indirect stale candidates could briefly resurrect a failed peer,
  and strict direct-only quarantine prevented a restarted peer known by one
  neighbor from propagating around the ring. The final rule blocks descriptors
  observed before disconnect while accepting direct or transitively propagated
  post-disconnect dissemination freshness as restart evidence.
- Acceptance containers intentionally remain running after success for
  inspection, matching the Phase 0.4/0.5 convention.
