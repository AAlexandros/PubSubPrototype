# Navigation (Vicinity) reference mapping

This module ports the topic-distance bucketing idea from the sibling
`PubSub-private` repository's `VicinityNav`/`MathPower` classes to Phase 0.5's
decentralized subscription model. It has no Cardano, event, HTTP, or Netty
dependencies, and does not depend on `securecyclon` or `peer-sampling-api`.

| Reference source under `PubSub-private/src/` | Runtime behavior |
| --- | --- |
| `util/MathPower.java:logDistance` | `FingerTopics.distance` computes the shortest cyclic distance between two ordinals modulo `T`. `FingerTopics.forSubscription` enumerates concrete finger-topic ordinals `x, x +/- b^0, x +/- b^1, ...` directly (a closed-form target set) rather than a logarithmic bucket index, since Phase 0.5's ranking already carries an explicit numeric distance per candidate. |
| `pubsub/VicinityNav.java:addNeighbor` | `NavigationView.offer` implements the same three cases (peer already known -> keep the fresher entry; free slot -> add; bucket full -> replace the worst entry only if strictly better) per finger-topic target, bounded to `capacity` (`c`) candidates. |
| `pubsub/VicinityNav.java` bucket ranking | Candidate ranking is exact-topic-first, then closest cyclic distance, then freshest, matching `VicinityNav`'s distance-then-freshness comparison; unlike the simulation (which has a global topic registry), a candidate's distance is `Integer.MAX_VALUE` until its real subscriptions are learned via gossip. |
| `pubsub/VicinityNav.java:importFromLinkables` | `NavigationEngine.ingestSample` accepts a raw `PeerSamplingService` sample (host/nodeId/port only) as a low-priority, unknown-subscription candidate; it never reads or mutates the SecureCyclon view itself. |
| `vic/Vicinity.java:nextCycle`/`doMyGossipPart` | `NavigationEngine.cycle` selects a partner from the flattened view, computes candidates useful to the partner's finger topics (derived from the partner's advertised subscriptions and the current `TopicOrdering`), and returns an `Outgoing` gossip request; `request`/`response` mirror the reference's receive-side gossip handling. |
| `registry/TopicRegistry.java` (global topic assignment) | Replaced by `TopicOrdering`, computed locally and deterministically from the Cardano Topic Registry's active topic ids (lexicographic sort, ordinals `0..T-1`), since Phase 0.5 has no global simulation oracle. |

## Runtime adaptations

- The reference simulation gives every node global, instant knowledge of every
  other node's single topic. Phase 0.5 subscriptions are local, private, and a
  node may hold several; a peer's subscriptions are unknown until learned
  through a real gossip exchange, so newly discovered SecureCyclon samples
  start as unranked (`Integer.MAX_VALUE` distance) fallback candidates.
- `VicinityNav` never expires its bucket entries within `addNeighbor`; a
  separate `Liveness` protocol notifies it of dead peers. This module instead
  prunes candidates once `now - freshness > staleAfterMs` on every cycle,
  since Phase 0.5 has no separate liveness detector wired into Navigation.
- Changing the active topic set changes what each ordinal number means, so
  `NavigationEngine.updateOrdering` clears the whole view (not just the
  affected buckets) and lets subsequent gossip cycles repopulate it; adding or
  removing a *subscription* (ordering unchanged) only prunes finger-topic
  targets that are no longer needed.
- Only the Navigation Layer (`VicinityNav` equivalent) is in scope for Phase 0.5.
  The ring-based same-topic dissemination layer (`VicinityTop`/`Dissemination`
  in the reference) is out of scope; Phase 0.3's temporary broadcast-forwarding
  path remains the event dissemination mechanism.

## Validation

`TopicOrderingTest`, `FingerTopicsTest`, and `NavigationViewTest` cover
deterministic ordering, cyclic distance/finger-topic generation, and Vicinity
selection (exact-topic-first, nearer-beats-farther, freshness tie-breaks,
bounded capacity, no self/duplicate entries) with deterministic fixtures.
`NavigationEngineTest` covers the gossip cycle, subscribe/unsubscribe
recomputation, and full-view invalidation on topic-ordering change.
`SubscriptionStoreTest` covers persistence across reloads. Application
integration tests (`NavigationIntegrationTest`) run real TCP connections
through the Netty transport, and the full devnet acceptance is
`scripts/acceptance/phase-0.5.sh`.
