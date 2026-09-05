# SecureCyclon reference mapping

This module ports the legitimate, non-tit-for-tat configuration in the sibling
`cyclon` repository at commit `0f3a9510fb7dfcbf961b61169363988bb4ab453c`.
It has no Cardano, event, HTTP, or Netty dependencies.

| Reference source under `cyclon/src/main/java/gr/aueb/cyclon/` | Runtime behavior |
| --- | --- |
| `core/CyclonPeer.java` | `SecureLink` stores creation timestamp, derived link ID, ownership chain and swappable flag. Ownership transfers append the receiver; samples do not transfer ownership. |
| `behavior/AbstractCyclonBehavior.java:execute` | Redeem/remove the oldest link, select up to swapLength minus one swappable links, prepend exactly one fresh self link, append the recipient to transferred chains, send view samples plus the redeemed link. |
| `behavior/AbstractCyclonBehavior.java:pollOldestNeighbor` | Stable descending timestamp sort, then remove the last entry. Ties follow the reference's insertion order. |
| `util/StructureUtils.java:createRandomSublist` | Shuffle eligible links and take a prefix. The runtime supplies a configurable seed; the original helper uses an unseeded shuffle. |
| `behavior/AbstractCyclonBehavior.java:passiveThread` | Exclude the sender and links locked by an active request; send up to swapLength owned links. Never create a fresh link in a response. |
| `behavior/AbstractCyclonBehavior.java:replacePeers` | Prefer free slots, then sent links, then non-swappable links. Sent links retained locally lose swap permission, but may still be redeemed. |
| `behavior/AbstractCyclonBehavior.java:checkReceivedPeers` | Check link ID and the receiver's position in the ownership chain. Runtime validation is always enabled. |
| `behavior/AbstractBlockerBehavior.java` | Retain the longest observed ownership chain, detect divergent prefixes, identify the last common owner, remove offenders, propagate reports, and expire observation history after `(viewSize + ageThreshold) * cycleIntervalMs`. Bootstrap timestamp zero is exempt from inconsistency detection. |

## Runtime adaptations

- The checked-in `cyclon.cfg` sets `ENABLE_TIT_FOR_TAT=false`. The optional
  tit-for-tat state machine and simulation-only `SIGNAL` scheduling are not enabled here.
- Node IDs are persistent Ed25519 public-key hashes; endpoints carry a listening
  host/port. Link IDs use SHA-256 of `nodeId:timestamp` rather than the simulation's
  numeric hash. Creation timestamps use Unix milliseconds instead of simulator time.
- Phase 0.4 forbids duplicate node IDs, whereas PeerNet permits multiple links to
  one node. A newer owned link replaces an older entry; a returned ownership chain
  can restore a retained link's swap permission without adding a second peer entry.
- A bootstrap seed is admitted once per process, only after HELLO resolves it.
  Reconnecting a seed does not reinsert or refresh it. An inbound connection by
  itself grants no peer-sampling entry. Restarted nodes bootstrap again.
- Real requests have correlation IDs and expire at the next cycle. Links already
  sent become non-swappable on timeout; the redeemed target is not restored.
  Dynamic connections use bounded connect/handshake timeouts and verify the learned
  node ID against HELLO. Only configured seed endpoints retry automatically.
- The reference's frequency detector exists but is disabled by a constant. This
  runtime enables its one-new-link-per-cycle rule, as explicitly required by
  Phase 0.4, alongside replay, structure, self, duplicate and endpoint checks.
- Reports include conflicting link observations; receivers validate the
  inconsistency rather than accepting an unsupported blacklist ID. These are
  metadata consistency checks with the simulation's trust assumptions: link
  ownership chains and the existing HELLO exchange are not cryptographic signatures.
- The reference expires the **observation history**, not all view entries at a
  fixed TTL. Departed peers leave the view through oldest-link redemption and
  replacement; a failed gossip cannot pin its target. An entirely isolated node
  still needs a joining peer/bootstrap restart to regain links, as in the reference.

## Validation

`SecureCyclonTest` covers selection, exchange construction, replacement, returned
ownership, generation limits, replay/forks/reports, invalid input, and an asymmetric
three-node failure/restart trace. `peernet-non-tit-for-tat.properties` is a
hand-traced deterministic conformance fixture, not output from an executed PeerNet
oracle. Application integration tests run real TCP connections and the actual
Netty/HELLO/gossip path. The complete devnet acceptance is
`scripts/acceptance/phase-0.4.sh`.
