# Hybrid Dissemination

`libs/dissemination` implements the transport-independent Phase 0.6 D2
Dissemination state machine. It owns an independent view for each locally
subscribed topic: the predecessor and successor in cyclic unsigned 256-bit
`NodeId` order plus configurable random same-topic links refreshed from
Navigation.

`DisseminationEngine` accepts same-topic candidates only through Navigation's
public `peersForTopic` API or through a validated `DisseminationExchange`. It
does not inspect or mutate Navigation, SecureCyclon, registry, validation, or
deduplication state. `DisseminationRuntime` in the node application supplies
transport and lifecycle integration.

When a transport peer disconnects, indirect stale candidates cannot immediately
reinsert it. A direct post-restart gossip message, or a descriptor freshly
observed by another dissemination peer after the disconnect, proves liveness
and permits the persistent identity to rejoin.

The random-link role is logically independent from ring roles. With a small
overlay the same physical peer may occupy a ring role and a random role; event
targets are de-duplicated before sending.
