


Software Requirements Specification
Cardano Pub/Sub Framework Research Prototype
(D2-aligned, incremental implementation)

Field	Value
Document version	0.1 - Baseline SRS
Date	25 August 2026
Initial deployment target	Local Cardano devnet
Initial runtime size	3 Java Pub/Sub nodes
Primary implementation language	Java
Cardano contract language	Aiken recommended; equivalent Plutus-family implementation acceptable
Primary architectural source	Cardano Pub/Sub Framework - Design and Architecture, Deliverable D2, September 2024
Purpose	Research prototype suitable as the implementation basis for a conference paper

Status: implementation baseline; requirements may be refined after the first executable milestones.

 1. Purpose and scope
This SRS defines an incremental research prototype of the Cardano Pub/Sub architecture described in Deliverable D2. The prototype shall begin with infrastructure and a three-node Java network, then add the on-chain topic registry, peer sampling, topic navigation, hybrid dissemination, and finally the event-persistence subsystem. The intent is to build an executable system that remains close to D2 while explicitly documenting any Cardano- or prototype-driven substitutions.
The initial three-node deployment is a functional-development target, not the final experimental scale. The software shall therefore be parameterized so later conference experiments can increase the number of processes/containers without redesigning the protocols.
1.1 Goals
"	Produce a repeatable local Cardano devnet and a reproducible three-node Java deployment.
"	Implement D2 topic administration on Cardano, including owners, administrators, publishers, retention period, and replication factor.
"	Implement the three-layer P2P overlay described in D2: SecureCyclon peer sampling, Vicinity-based navigation, and same-topic hybrid dissemination.
"	Implement D2 event persistence using a globally known replication-server set and a one-hop clique DHT.
"	Collect protocol and system metrics suitable for a research evaluation and later conference-paper figures/tables.
"	Keep protocol logic modular enough to compare the real implementation against the existing Java PeerNet/PeerSim-family simulations.
1.2 Non-goals for version 1
"	Production deployment, mainnet operation, production wallet custody, or economic security.
"	The replication-server incentive, reward, deposit-slashing, Proof-of-Replication, or Proof-of-Retrievability mechanism. D2 explicitly leaves this line of work open.
"	Private-topic subscriber authorization or key-distribution protocols. D2 recommends encryption when confidentiality is required but does not specify the mechanism.
"	Internet-scale NAT traversal, relay discovery, DDoS resistance, or production PKI/TLS deployment.
"	Performance claims based only on the initial three-node deployment.
2. Architectural basis and interpretation rules
Requirements marked as D2-derived preserve the report's terminology and design intent. Where D2 is algorithmic rather than implementation-specific, this SRS specifies an implementation boundary and acceptance behavior without inventing details that D2 does not provide. Where the Cardano eUTxO model requires a different realization from a conventional mutable smart-contract object, the substitution is called out explicitly.
2.1 D2 components in scope
D2 area	Prototype component	Initial status
Chapter 2 - Pub/Sub Administration	Cardano Topic Registry contract + Java Registry Adapter	Required
3.3.1 - Peer Sampling Layer	SecureCyclon-compatible Java protocol module	Required; exact protocol details depend on SecureCyclon paper/simulation specification
3.3.2 - Navigation Layer	Vicinity-style topic navigation and finger-topic views	Required
3.3.3 - Dissemination Layer	Same-topic ring + random link Hybrid Dissemination	Required
4.1 - Resource Provisioning	Replication-server registration only	Partial; incentives/penalties deferred
4.2 - Key-Value Store	One-hop clique DHT over registered replication servers	Required
4.3 - Indexing Scheme	topic/publisher/sequence event keys + lightweight topic publisher log	Required

2.2 D2 ambiguities or gaps to preserve explicitly
"	D2 enumerates six topic properties but does not include administrators in that list, while its API includes addAdmin/removeAdmin and createTopic(admins[]). This SRS treats administrators as explicit topic metadata because the D2 API requires them.
"	D2 defines topics as a cyclic enumeration 0..T-1 for navigation, but does not specify how that enumeration changes when topics are created or deleted. The prototype uses a deterministic active-topic ordering derived from topicId and rebuilds finger targets after registry changes. This is a prototype decision and must be reported as such.
"	D2 names SecureCyclon and Vicinity but does not contain their complete executable state machines. Exact protocol-message fields, SecureCyclon cryptographic link rules, and cycle operations shall be taken from their published specifications and/or the project simulations when implementing those phases.
"	D2 allows replication-server registration either in the topic registry or in a separate smart contract. This SRS uses a separate replication-registry state for modularity, while keeping both under the Cardano administration subsystem.
3. System context and target architecture
The prototype consists of five logical subsystems. A single Java node process may host several roles in the three-node deployment to reduce operational complexity.
                    Local Cardano Devnet
                          |
                 +--------+---------+
                 | Topic / Replica  |
                 | registry state   |
                 +--------+---------+
                          |
                 Registry Adapter
                          |
    +---------------------+---------------------+
    |                     |                     |
+---+----+            +---+----+            +---+----+
| Node 1 |<---------->| Node 2 |<---------->| Node 3 |
+---+----+            +---+----+            +---+----+
    |                     |                     |
    | Peer Sampling -> Navigation -> Dissemination
    |                     |                     |
    +---------- one-hop replication clique ------+

3.1 Runtime role model
"	Pub/Sub node: participates in peer sampling, navigation, subscription, publishing, and dissemination.
"	Replication server: stores event replicas and topic publisher logs; initially may be enabled on the same three Java processes.
"	Cardano registry client: submits and observes administrative transactions.
"	Experiment harness: starts/stops nodes, injects faults, assigns workloads, and collects metrics.
"	Contract deployment tool: builds and deploys the topic/replication registry scripts to the devnet.
3.2 Recommended implementation technologies (non-normative)
"	Java 21 or newer LTS-compatible runtime; multi-module Gradle build.
"	Netty or Java NIO for the P2P transport. The SRS requires behavior, not a specific networking library.
"	JSON for first milestones for debuggability; CBOR may replace it when protocol formats stabilize.
"	Aiken for Cardano validators/minting policies, because it keeps the on-chain component small and independently testable. A Plutus-family equivalent is acceptable.
"	Docker Compose for the three Java nodes and supporting services; devnet scripts may run Cardano nodes directly or in containers.
"	JUnit 5 for unit/integration tests and structured JSON/CSV experiment output.
4. System constraints and assumptions
"	Initial acceptance tests use exactly three Pub/Sub node processes, but node count shall be configuration-driven.
"	All initial nodes run on one development host or one local Docker network; public-Internet networking is not required.
"	A local Cardano devnet is the first ledger target. Preprod is a later deployment target, not a prerequisite for the base prototype.
"	The system may use static bootstrap endpoints before SecureCyclon has converged. Static peer lists shall not be used as the steady-state discovery mechanism after the peer-sampling milestone.
"	Randomized protocol choices shall support deterministic seeds for repeatable experiments.
"	Protocol clocks/cycles shall be configurable so simulation parameters can be mapped to the runtime.
"	A node may hold publisher/subscriber and replication-server roles simultaneously in the first prototype.
5. Incremental implementation plan and milestone gates
Each milestone shall leave the repository in a runnable state. Later milestones may depend only on interfaces stabilized by earlier milestones. A milestone is complete only when its exit criteria are automated or documented as repeatable commands.
Milestone	Scope	Exit criteria
M0 - Repository and Cardano devnet baseline	Create the repository structure, build system, local devnet, funded test identities, reset/start/stop scripts, and health checks.	Devnet produces blocks; registry deployment identity has funds; a clean reset is repeatable; Java build/test passes from a clean checkout.
M1 - Three-node transport and ping	Run three Java processes with static bootstrap configuration. Establish connections and exchange PING/PONG messages.	Each node can reach the other two; RTT and connectivity are logged; restarting one node causes peers to detect loss and reconnect after it returns.
M2 - D2 Topic Registry on Cardano	Implement and deploy the topic registry and a Java registry adapter.	Create/update/delete topic scenarios pass; unauthorized state changes fail; all three nodes observe the same registry state.
M3 - Node identity and signed event envelope	Introduce stable node identities, event IDs, signatures, duplicate detection, and open/moderated topic authorization checks.	Valid open-topic event accepted; valid authorized moderated event accepted; unauthorized/invalid signature rejected; duplicates are not reprocessed.
M4 - Peer Sampling Layer	Replace steady-state static peer discovery with SecureCyclon-compatible peer sampling while retaining static peers only for bootstrapping.	Views evolve over cycles; failed peer entries age out; three-node overlay stays connected; deterministic seeded test produces repeatable view transitions.
M5 - Navigation Layer	Implement D2 topic cyclic ordering, finger targets, Vicinity-style neighbor selection, and route-to-topic behavior.	A node starting from an arbitrary peer can discover a subscriber of a target topic; navigation view matches expected targets for deterministic test fixtures.
M6 - Dissemination Layer	Build and maintain same-topic predecessor/successor ring links and random same-topic links, then disseminate events over both.	All online subscribers receive a new event once; ring links converge for 3+ same-topic nodes; event forwarding and overhead metrics are emitted.
M7 - Replication registry and one-hop DHT	Register replication servers, build clique membership, implement deterministic replica placement and PUT/GET.	For each event key, all nodes compute the same R target servers; replicas can be written and read; expiration honors topic retention.
M8 - Missed-event recovery	Implement publisher sequence numbers, per-topic publisher log, offline interval detection, and parallel retrieval.	A subscriber deliberately disconnected for N events reconnects and retrieves exactly the missing event range.
M9 - Replica health and repair	Implement liveness checks and D2-style repair after confirmed replication-server failure.	When a replica server is stopped, failure is confirmed after configured probes and lost replica responsibilities are restored on the new target set.
M10 - Conference experiment harness	Automate parameterized runs, fault injection, workload generation, metric aggregation, and provenance capture.	A single command produces a run directory containing configuration, raw events, metrics, and summary files sufficient to reproduce a paper figure/table.

6. Functional requirements
6.1 Devnet and build environment
ID	Requirement (SHALL)	Priority	Verification
FR-ENV-001	The repository shall provide one documented command or script to initialize and start a local Cardano devnet from a clean state.	Must	Fresh-machine/dev-container runbook
FR-ENV-002	The devnet setup shall provide funded test credentials for contract deployment and at least three prototype node operators.	Must	Balance query
FR-ENV-003	The environment shall provide start, stop, status, and reset operations that do not require manual editing of generated files.	Must	Scripted integration test
FR-ENV-004	The project shall build all Java modules and run unit tests from a clean checkout using one build command.	Must	CI/local clean build
FR-ENV-005	The environment shall expose the active network parameters required by the registry adapter rather than hard-coding them in Java source.	Must	Configuration review
FR-ENV-006	The three-node deployment shall be launchable with a single compose/script command after the devnet is healthy.	Must	End-to-end startup test

6.2 Base Java node and transport
ID	Requirement (SHALL)	Priority	Verification
FR-NET-001	Each node shall expose a stable NodeId derived from its configured P2P public key.	Must	Unit test
FR-NET-002	Each node shall listen on a configurable host/port and shall advertise an endpoint usable by other prototype nodes.	Must	Three-node integration test
FR-NET-003	The transport shall support versioned, length-delimited protocol messages and reject malformed frames without terminating the process.	Must	Malformed-message test
FR-NET-004	The base protocol shall implement PING and PONG containing sender NodeId, message/correlation ID, and timestamps sufficient to calculate RTT.	Must	M1 acceptance test
FR-NET-005	Each node shall detect connection loss and retry according to configurable backoff parameters.	Must	Restart-one-node test
FR-NET-006	The runtime shall separate transport I/O from protocol state machines so peer-sampling/navigation/dissemination logic can be unit-tested without sockets.	Must	Architecture/code review
FR-NET-007	Message handlers shall be non-blocking with respect to network I/O; long-running ledger/storage operations shall execute outside the event-loop/I/O thread.	Should	Code review + stress test

6.3 Topic Registry requirements
D2 defines administrative methods using conventional smart-contract method notation. On Cardano, the same behavior shall be represented as validated eUTxO state transitions rather than assumed mutable contract methods.
ID	Requirement (SHALL)	Priority	Verification
FR-REG-001	Creating a topic shall produce a globally unique 256-bit topicId that cannot be reassigned to a future topic.	Must	Create/delete/recreate test
FR-REG-002	A topic datum/state shall contain topicId, name/description, owners, administrators, publishers, replication factor R, retention period T, and active/deleted status.	Must	Datum decode test
FR-REG-003	The transaction that creates a topic shall establish at least one owner, with the creator included as an owner.	Must	Validator test
FR-REG-004	Only an owner shall be able to delete/deactivate a topic, add/remove owners, or add/remove administrators.	Must	Positive/negative validator tests
FR-REG-005	Removing an owner shall be rejected if it would leave the topic without an owner.	Must	Negative validator test
FR-REG-006	An owner or administrator shall be able to add/remove publishers and update R and T.	Must	Validator tests
FR-REG-007	An empty publisher list shall represent an open topic; a non-empty list shall represent a moderated topic.	Must	State + event authorization test
FR-REG-008	Deleting a topic shall not make its topicId reusable. The prototype should preserve a tombstone/ inactive state sufficient to demonstrate this property.	Must	Lifecycle test
FR-REG-009	All three Java nodes shall be able to obtain the same canonical active-topic registry snapshot from the devnet.	Must	Three-node observation test
FR-REG-010	The registry adapter shall expose createTopic, deleteTopic, add/removeOwner, add/removeAdmin, add/removePublisher, setReplicationFactor, and setRetentionPeriod operations with semantics matching D2.	Must	Adapter integration test
FR-REG-011	The Java runtime shall cache registry state but shall tolerate cache invalidation/rebuild after chain updates or node restart.	Should	Restart/cache test

6.4 Event and identity requirements
ID	Requirement (SHALL)	Priority	Verification
FR-EVT-001	Every event shall contain protocolVersion, topicId, publisher identity, publisher sequence number, publication timestamp, payload, and publisher signature.	Must	Serialization test
FR-EVT-002	eventId shall be deterministic for an immutable event and shall be suitable for duplicate suppression.	Must	Unit test
FR-EVT-003	Nodes shall verify event integrity/signature before accepting an event into dissemination or persistence.	Must	Tamper test
FR-EVT-004	For moderated topics, nodes shall discard events whose publisher identity is not present in the current topic publisher list.	Must	Authorization test
FR-EVT-005	For open topics, any validly signed publisher identity may publish.	Must	Open-topic test
FR-EVT-006	Each publisher shall maintain a monotonically increasing per-topic sequence number beginning at zero.	Must	Restart/persistence test
FR-EVT-007	A node shall remember recently seen eventIds for a configurable deduplication window and shall not redeliver/re-forward duplicates as new events.	Must	Duplicate injection test

6.5 Peer Sampling Layer requirements
D2 requires SecureCyclon as the peer-sampling substrate. D2 does not specify its complete wire/state-machine algorithm. Therefore FR-PSC-001 through FR-PSC-007 define the observable runtime contract; the exact SecureCyclon exchange rules are an external algorithm dependency for implementation.
ID	Requirement (SHALL)	Priority	Verification
FR-PSC-001	Each node shall maintain a bounded peer-sampling view independent from navigation and dissemination views.	Must	State inspection
FR-PSC-002	Peer-sampling execution shall occur in configurable cycles and shall be driven by an injectable clock/scheduler for deterministic tests.	Must	Unit test
FR-PSC-003	Peer descriptors shall include at least NodeId, network endpoint, link age/freshness metadata required by the chosen SecureCyclon implementation, and authenticity data required by SecureCyclon.	Must	Serialization/state test
FR-PSC-004	The implementation shall support bootstrap from one or more configured seed peers when the local view is empty.	Must	Cold-start test
FR-PSC-005	After bootstrap, the protocol shall continuously refresh peer samples and shall remove or age-out unavailable peers.	Must	Churn test
FR-PSC-006	The layer shall expose random peer samples to the Navigation Layer through an internal Java interface without sharing mutable view state.	Must	Unit/integration test
FR-PSC-007	SecureCyclon-specific anti-manipulation rules shall be implemented as specified by the SecureCyclon algorithm used by the project; deviations shall be documented in the experiment manifest.	Must	Protocol conformance tests

6.6 Navigation Layer requirements
ID	Requirement (SHALL)	Priority	Verification
FR-NAV-001	Each registry snapshot shall define a deterministic cyclic ordering of active topics. Prototype rule: sort active 256-bit topicIds lexicographically and assign ordinal positions 0..T-1.	Must	Deterministic fixture test
FR-NAV-002	The navigation routing base b shall be configurable and shall default to 2.	Must	Configuration test
FR-NAV-003	Each node shall compute finger-topic targets at distances b^i in clockwise and anticlockwise directions while the distance does not exceed T/2, plus its own topic, as described in D2.	Must	Selection-function unit tests
FR-NAV-004	The number c of retained peers per finger topic shall be configurable.	Must	Configuration/state test
FR-NAV-005	The navigation layer shall use its current view plus candidates obtained through gossip/peer sampling to retain candidates closest to each finger topic, preferring fresher links among candidates of the same topic.	Must	Deterministic selection test
FR-NAV-006	A node requesting a target topic shall be forwarded/referred to the known peer whose topic ordinal is closest to the target according to the cyclic metric.	Must	Route test
FR-NAV-007	Registry changes that alter active-topic ordering shall trigger recomputation of finger targets without process restart.	Should	Dynamic topic test
FR-NAV-008	The layer shall expose same-topic random neighbors to the Dissemination Layer.	Must	Integration test

6.7 Dissemination Layer requirements
ID	Requirement (SHALL)	Priority	Verification
FR-DIS-001	For each subscribed topic, nodes shall maintain a dissemination view separate from peer-sampling and navigation views.	Must	State inspection
FR-DIS-002	Within a topic, nodes shall be ordered by NodeId in modulo arithmetic and shall converge toward predecessor and successor ring neighbors.	Must	Three-node same-topic convergence test
FR-DIS-003	The layer shall retain at least one random same-topic link supplied by the Navigation Layer, configurable for later experiments.	Must	State inspection
FR-DIS-004	A locally published or first-seen event shall be forwarded over the applicable ring/random links according to the D2 Hybrid Dissemination behavior and shall not be sent immediately back over the incoming link.	Must	Message-trace test
FR-DIS-005	A node receiving an already-seen event shall suppress further processing/forwarding except for protocol bookkeeping explicitly required by the implementation.	Must	Duplicate flood test
FR-DIS-006	The dissemination layer shall expose delivery callbacks to the local subscriber application only after signature/topic authorization checks pass.	Must	Authorization integration test
FR-DIS-007	The runtime shall record per-event first-seen time, delivery time, incoming peer, outgoing peers, hop metadata if enabled, and duplicate count for evaluation.	Must	Metrics schema test

6.8 Replication registry and persistence requirements
ID	Requirement (SHALL)	Priority	Verification
FR-PER-001	A replication server shall have a unique server/node ID, network endpoint, public key/identity binding, active status, and commitment metadata sufficient for the prototype registry.	Must	Registry decode test
FR-PER-002	The active replication-server set shall be globally observable from Cardano state.	Must	Three-node consistency test
FR-PER-003	Every active replication server shall maintain the complete active server membership view required to realize D2's clique DHT.	Must	Membership comparison test
FR-PER-004	The event storage key shall be hash(topicId || publisherPublicKey || sequenceNumber), preserving D2's indexing scheme.	Must	Known-vector unit test
FR-PER-005	Given an event key and replication factor R, all servers shall deterministically select the same R responsible servers by circular ID distance.	Must	Known-vector unit test
FR-PER-006	A PUT submitted to any replication server shall be forwarded/stored on the R responsible servers without requiring multi-hop DHT routing.	Must	Integration test
FR-PER-007	A GET submitted to any replication server shall identify the responsible replica set and retrieve the record from one available responsible server.	Must	Integration test
FR-PER-008	Stored event records shall include expiry information derived from the topic retention period T and shall become unavailable after expiry.	Must	Accelerated-retention test
FR-PER-009	The persistence layer shall store a lightweight per-topic publisher log addressable by hash(topicId), containing each relevant publisher's latest sequence number and timestamp.	Must	Storage test
FR-PER-010	A recovered subscriber shall query the topic publisher log for its offline interval, determine missing sequence ranges, construct event keys locally, and retrieve missing events concurrently.	Must	M8 acceptance test
FR-PER-011	Failure detection shall require a configurable number of consecutive unsuccessful probes before a replication server is considered unavailable. Defaults should initially match D2's recommendation of three attempts with five-second timeouts, but shall be tunable.	Should	Fault-injection test
FR-PER-012	After a confirmed replication-server failure, active servers shall recompute responsibility and restore required replicas from surviving copies when possible.	Should	M9 acceptance test

6.9 Prototype command/API requirements
ID	Requirement (SHALL)	Priority	Verification
FR-API-001	The prototype shall expose command-line or local HTTP operations to create/delete topics and change owners/admins/publishers/R/T through the registry adapter.	Must	CLI/API integration test
FR-API-002	The prototype shall expose subscribe(topicId), unsubscribe(topicId), and publish(topicId,payload) operations for experiments.	Must	End-to-end test
FR-API-003	The prototype shall expose node status including identity, registry version/tip, subscriptions, and per-layer neighbor views.	Must	Status query
FR-API-004	The prototype shall expose persistence status including active replica servers, records stored, and current responsibility set.	Should	Status query
FR-API-005	Administrative/test APIs shall be bound to localhost or the private experiment network by default.	Must	Configuration review

6.10 Experiment instrumentation requirements
ID	Requirement (SHALL)	Priority	Verification
FR-MET-001	Every experiment run shall have a unique runId and a machine-readable configuration snapshot.	Must	Run-directory inspection
FR-MET-002	Random choices shall be driven by recorded seeds so a run can be repeated with equivalent protocol randomness.	Must	Repeatability test
FR-MET-003	Nodes shall emit structured logs containing runId, nodeId, timestamp, subsystem, event type, and relevant correlation/event IDs.	Must	Schema validation
FR-MET-004	The system shall collect D2 dissemination metrics: hit ratio, dissemination completion time/latency, message overhead, and per-node load distribution.	Must	Metric aggregation test
FR-MET-005	The system shall collect overlay metrics including peer-view churn, navigation convergence/target correctness, dissemination ring correctness, and time to recover from induced failure.	Must	Experiment harness test
FR-MET-006	The harness shall support controlled node start, graceful stop, crash/kill, temporary network isolation where feasible, publish workload, and subscriber offline/reconnect scenarios.	Must	Fault-injection acceptance
FR-MET-007	Raw data shall be exportable to CSV or JSON without requiring the running system in order to generate later paper plots.	Must	Offline analysis test

7. Core data models and protocol records
Field names below are logical. Wire/Cardano encodings may use compact numeric labels, CBOR, or Aiken datum constructors as long as the logical information is preserved.
Record	Required fields / notes
NodeIdentity	nodeId: 256-bit hash; p2pPublicKey; endpoint; optional Cardano credential/address binding.
PeerDescriptor	NodeIdentity reference + protocol/link metadata such as age/freshness and supported protocol version.
TopicState	topicId; topicName/description; owners[]; admins[]; publishers[]; replicationFactor R; retentionPeriod T; active flag; optional state/version marker.
SubscriptionState	topicId; join time; last delivered sequence per publisher; dissemination predecessor/successor; random same-topic peers.
EventEnvelope	protocolVersion; topicId; publisherPublicKey or key hash; sequenceNumber; timestamp; payload bytes; signature; deterministic eventId.
ReplicationServerState	serverId; public key/credential; endpoint; active/commitment metadata; optional prototype deposit metadata.
StoredEvent	key; EventEnvelope; storedAt; expiresAt; topicId; source/replica metadata.
TopicPublisherLog	topicId; entries of publisher -> latestSequenceNumber + latestTimestamp; update metadata.
ExperimentConfig	runId; node count; random seed(s); cycle lengths; b; c; fanout/random-link count; R/T; workload/fault scenario; build/commit identifier.

8. Cardano contract realization
This section defines the required Cardano realization, not an Ethereum-style API. The recommended design is one topic state UTxO per topic, identified by a unique topic token/NFT or equivalent uniquely minted asset. The topicId shall be a 256-bit identifier derived from that unique creation. Administrative operations consume the current topic UTxO and produce a new validated state UTxO.
8.1 Topic lifecycle state transitions
D2 method	Cardano prototype realization	Authorization
createTopic(...)	Mint/create unique topic identifier and create initial TopicState UTxO.	Creator transaction signature; creation policy rules.
deleteTopic(topicId)	Consume active TopicState and recreate it as inactive/tombstone (or equivalent non-reusable terminal state).	Owner only.
addOwner/removeOwner	Consume/recreate TopicState with modified owners list; validator prevents zero owners.	Owner only.
addAdmin/removeAdmin	Consume/recreate TopicState with modified admins list.	Owner only.
addPublisher/removePublisher	Consume/recreate TopicState with modified publishers list.	Owner or admin.
setReplicationFactor	Consume/recreate TopicState with new R.	Owner or admin.
setRetentionPeriod	Consume/recreate TopicState with new T.	Owner or admin.

8.2 Registry enumeration
The Java Registry Adapter shall enumerate all active topic state UTxOs controlled by the registry policy/script and construct the current active-topic snapshot. For the first prototype, polling is acceptable if deterministic and adequately instrumented. A later indexer/chain follower may replace polling behind the same adapter interface.
8.3 Publisher identity binding
D2 speaks of publisher public keys and Cardano accounts. To avoid coupling the networking prototype to wallet-internal key formats, the prototype may store a P2P/event-signing public key (or its hash) in the on-chain publisher list, while topic administration remains authorized by Cardano transaction signers. If separate keys are used, the binding is a prototype deviation and shall be documented. A later version may use a Cardano-compatible signing key directly for event signatures.
9. Java module boundaries
The implementation should be a multi-module project so the same algorithmic code can be exercised by deterministic tests and compared with simulation behavior.
Module	Responsibility
node-model	Immutable IDs, topic state, event records, peer descriptors, configuration.
transport	Connections, framing, PING/PONG, request/response correlation, reconnect.
registry-api	Java interface for registry state and administrative transactions.
registry-cardano	Devnet/Cardano implementation of registry-api.
protocol-peer-sampling	SecureCyclon state machine/view; no direct socket code.
protocol-navigation	Topic ordering, finger targets, Vicinity selection and routing.
protocol-dissemination	Ring maintenance, random links, event forwarding and deduplication.
persistence	Replication membership, placement, storage, lookup, retention, repair.
node-runtime	Composition root tying transport, Cardano adapter, protocols, and local API together.
experiment-harness	Scenario orchestration, fault injection, metrics collection, run manifests.

10. Internal interfaces
The exact Java names may change, but the separation of concerns is normative.
interface PeerSampler {
    List<PeerDescriptor> sample(int count);
    void onCycle();
    void onPeerMessage(PeerMessage message, PeerDescriptor from);
}

interface TopicRegistryView {
    RegistrySnapshot currentSnapshot();
    Optional<TopicState> topic(TopicId id);
    void addListener(RegistryChangeListener listener);
}

interface TopicNavigator {
    Optional<PeerDescriptor> nextHop(TopicId target);
    List<PeerDescriptor> sameTopicSamples(TopicId topic, int count);
}

interface Disseminator {
    void subscribe(TopicId topic);
    void publish(EventEnvelope event);
    void onEvent(EventEnvelope event, PeerDescriptor from);
}

interface EventStore {
    PutResult put(TopicState topic, EventEnvelope event);
    Optional<EventEnvelope> get(TopicState topic, EventKey key);
    List<EventEnvelope> recoverMissing(TopicId topic, OfflineCursor cursor);
}

11. Non-functional requirements
ID	Requirement (SHALL)	Priority	Verification
NFR-001	The prototype shall be reproducible from a documented clean environment and shall not depend on manually edited runtime state.	Must	Clean-room run
NFR-002	No protocol shall hard-code the number three; node count, topic count, b, c, cycles, R, T, timeouts, and workload rates shall be configurable.	Must	Configuration/code review
NFR-003	Protocol modules shall be deterministic when provided the same initial state, clock events, incoming messages, and random seed.	Must	Repeatability unit test
NFR-004	Node processes shall survive malformed/unsupported peer messages by rejecting them and continuing service.	Must	Fuzz/negative test
NFR-005	All wire messages shall contain a protocol version to allow controlled evolution during the research project.	Must	Serialization test
NFR-006	The three-node M1 ping test shall run continuously for at least 10 minutes without unhandled exceptions or leaked connections.	Must	Soak test
NFR-007	Local end-to-end event dissemination among three nodes shall complete within a configurable test timeout; the SRS does not prescribe a publication-performance target before benchmarking.	Must	E2E test
NFR-008	Experiment logs shall use a consistent clock basis and preserve both wall-clock timestamps and monotonic durations where applicable.	Must	Metrics review
NFR-009	Secrets/keys used by the devnet shall be clearly marked as test-only and shall not be presented as production key-management practice.	Must	Documentation review
NFR-010	The system shall emit enough state to diagnose why a node chose a navigation/dissemination neighbor during deterministic tests.	Should	Debug trace test
NFR-011	Core selection functions, key placement functions, authorization checks, and event-ID computation shall have unit tests with fixed vectors.	Must	Test report

12. Verification and test strategy
12.1 Test levels
"	Pure unit tests: ring arithmetic, topic-distance arithmetic, finger calculation, Vicinity selection, replica placement, event key/event ID, signature checks.
"	Protocol state-machine tests: deterministic cycle-by-cycle execution without sockets using fake clocks and seeded random sources.
"	Three-node transport tests: Docker/local processes, connection/reconnection, PING/PONG, malformed input.
"	Cardano contract tests: positive and negative validator/minting-policy paths for each administrative operation.
"	End-to-end devnet tests: create topic -> subscribe -> publish -> disseminate -> persist -> disconnect -> publish -> reconnect -> recover.
"	Fault tests: process kill, peer restart, replication-server loss, delayed registry observation.
"	Longer experiment runs: repeated randomized workloads with recorded seed and run manifest.
12.2 Minimal end-to-end acceptance scenario
1.	Start the Cardano devnet from a clean state and deploy registry scripts.
2.	Start Java nodes N1, N2, and N3; verify pairwise PING/PONG and node-status endpoints.
3.	Create topic A on-chain with N1 as owner/admin and a chosen publisher policy; all nodes observe topic A.
4.	Subscribe N1, N2, and N3 to topic A and allow peer sampling/navigation/dissemination views to stabilize.
5.	Publish event A/0 from an authorized publisher; all online subscribers receive exactly one application delivery.
6.	Enable replication with R=2 and verify that event A/0 is placed on exactly the deterministic responsible servers.
7.	Disconnect N3; publish A/1 and A/2; reconnect N3 and verify recovery of exactly A/1 and A/2.
8.	For a moderated topic, attempt publication from an unauthorized key and verify rejection before forwarding/persistence.
9.	Stop one replication server and verify the configured failure detector and replica repair after the M9 milestone.
13. Conference-paper evaluation requirements
Three nodes are sufficient to validate integration and illustrate the architecture, but they are not sufficient for convincing scalability or probabilistic dissemination claims. The codebase shall therefore make later N-node experiments an execution/configuration problem rather than an architecture rewrite.
13.1 Candidate research questions
"	RQ1: Does the three-layer overlay converge and self-heal in a real asynchronous implementation in the same qualitative way as the PeerNet/PeerSim-family simulations?
"	RQ2: What dissemination latency, hit ratio, duplicate/message overhead, and load distribution result from ring + random-link Hybrid Dissemination under controlled failures?
"	RQ3: What latency and operational overhead are introduced by using Cardano as the globally consistent topic/replication-server registry?
"	RQ4: Can the proposed one-hop persistence design recover missed events and repair replica placement correctly under server failure?
"	RQ5: Which parameters observed in simulation (cycle duration, view size, b, c, random fanout) transfer well to a networked prototype, and which require retuning?
13.2 Required measurement outputs
Measurement	Definition / source
Hit ratio	Unique intended subscribers receiving an event / intended online subscribers.
Dissemination latency	Publication time to first/median/last subscriber delivery; report local monotonic durations.
Message overhead	Total event forwards, duplicate receives, and control messages per delivered event.
Load distribution	Per-node bytes/messages sent/received and events forwarded.
Overlay convergence	Time/cycles until expected ring/finger relationships meet a defined correctness threshold.
Churn recovery	Time/cycles from fault injection to repaired peer/navigation/dissemination state.
Registry propagation	Ledger transaction submission/confirmation and time until each node observes the updated registry snapshot.
Persistence recovery	Missing events identified, requested, successfully retrieved, and elapsed recovery time.
Replica repair	Time from confirmed server loss to restored target replication factor.

13.3 Simulation-to-prototype comparability
"	Each runtime protocol parameter shall have a named configuration key and unit, with a short mapping note to the corresponding PeerNet/PeerSim simulation parameter where one exists.
"	Selection functions and ring-distance calculations should be implemented as pure Java functions/classes so deterministic vectors can be shared conceptually with simulation tests.
"	The experiment manifest shall record differences between simulated time/links and real sockets, including timeouts, scheduling jitter, serialization, and ledger delays.
"	The paper shall not claim direct numerical equivalence between simulation and prototype unless the experiment methodology explicitly controls those differences.
14. Prototype security requirements
"	Event signatures and publisher authorization are in scope because they directly affect D2 moderated-topic semantics.
"	On-chain administrative authorization is in scope for every registry state transition.
"	SecureCyclon security properties are in scope to the extent required by the selected SecureCyclon specification; their precise realization must be separately conformance-tested.
"	Transport encryption is optional for the local devnet prototype unless required by the chosen SecureCyclon mechanism; test network isolation is acceptable for v1.
"	All deserialized lengths/counts shall be bounded to avoid accidental memory exhaustion during experiments.
"	Test keys shall not be reused for production or public mainnet funds.
15. Explicit prototype substitutions and deferred features
Area	D2 design	Prototype decision
Cardano registry programming model	Smart contract with method-like API and global registry state.	Represent topic administration as eUTxO state transitions; Java adapter exposes the D2 method semantics.
Topic ID	Registry-assigned unique 256-bit ID, never reused.	Derive from uniquely created/minted topic identity; preserve inactive/tombstone state or equivalent proof of non-reuse.
Topic ordering	Global cyclic enumeration 0..T-1.	Deterministically sort active topicIds and assign ordinals per registry snapshot; recompute after changes.
Replication incentives	Deposits, rewards, penalties, proof of service discussed but left as work in progress.	Deferred. Registration and fault behavior only; optional dummy/test deposit metadata may be recorded but has no economic claim.
Replication servers	Likely SPO-operated dedicated nodes.	Initially the three Java nodes may also act as replication servers.
Subscriber confidentiality	All topics public; encryption/private key channels if confidentiality needed.	No confidentiality/key-distribution layer in v1.
Bootstrap	D2 assumes arbitrary contact to an overlay node.	Use one or more configured bootstrap peers for cold start; peer sampling takes over afterward.
Cardano observation	Global on-chain knowledge.	Polling is acceptable initially behind a Registry Adapter; later replaceable with an indexer/chain follower.

16. Repository and deliverable structure
/README.md
/docs/
  srs/
  architecture-decisions/
  experiment-methodology/
/infra/
  devnet/
  docker/
/contracts/
  topic-registry/
  replication-registry/
/java/
  node-model/
  transport/
  registry-api/
  registry-cardano/
  protocol-peer-sampling/
  protocol-navigation/
  protocol-dissemination/
  persistence/
  node-runtime/
  experiment-harness/
/experiments/
  scenarios/
  analysis/

Each milestone should add an Architecture Decision Record (ADR) when a prototype choice differs materially from D2, especially for Cardano state layout, topic enumeration, identity binding, and SecureCyclon implementation details.
17. Principal implementation risks
Risk	Impact	Mitigation
SecureCyclon details are not fully specified in D2	Could delay M4 or produce a non-faithful security layer.	Treat SecureCyclon paper/project simulation as a required external algorithm specification; isolate the module so a basic peer-sampling fallback can be used temporarily without claiming equivalence.
Three nodes are too small for research conclusions	Functional success could be mistaken for scalability evidence.	Make N configurable from day one; reserve larger containerized experiments for M10.
Cardano eUTxO state shape complicates D2 method mapping	Contract iteration could block networking work.	Keep Registry Adapter interface stable and use a mock/in-memory implementation for protocol tests while contract work proceeds.
Dynamic topic enumeration causes navigation churn	Finger views can change after topic creation/deletion.	Instrument registry version and view rebuilds; document sorted-topicId mapping as a prototype choice and evaluate alternatives later.
Real-time scheduling differs from simulation cycles	Prototype may not reproduce simulation curves.	Inject clocks/random sources, record scheduler lag, and compare qualitative/normalized metrics rather than assume equality.
Replication repair can become complex	M9 may expand scope.	Implement correctness for a small clique first; postpone optimizations and incentives.
Java/Cardano integration library instability	Could create tooling friction.	Keep Cardano access behind adapter; permit CLI/subprocess or local service integration initially, replace later without protocol changes.

18. D2 traceability matrix
D2 section	D2 concept	SRS coverage
2.1	Topic registry and topic properties	FR-REG-001..011; Section 8
2.2	Administrative API	FR-REG-010; Section 8.1
3.1	Reliability/efficiency objectives	FR-DIS; FR-MET-004..005; Section 13
3.1.1	Random links / fanout	FR-DIS-003..004
3.1.2	Harary graph reliability	FR-DIS-002 (H2 ring prototype)
3.1.3	Hybrid Dissemination	FR-DIS-003..007
3.2	Navigation across topics	FR-NAV-001..008
3.3	Three-layer overlay	Sections 3 and 9; FR-PSC/NAV/DIS
3.3.1	SecureCyclon peer sampling	FR-PSC-001..007
3.3.2	Vicinity navigation	FR-NAV-003..008
3.3.3	Vicinity dissemination ring	FR-DIS-001..003
4.1.1	Replication-server registration/commitment	FR-PER-001..003
4.1.2	Incentives/penalties	Explicitly deferred - Section 15
4.2.2	One-hop clique DHT and recovery	FR-PER-003..007, 011..012
4.3	Event key and topic publisher log	FR-PER-004, 009..010; FR-EVT-006

19. Prototype definition of done
The D2-aligned research prototype is considered functionally complete when all Must requirements through M8 pass and the following demonstration can be repeated from a clean devnet:
10.	Start/reset a local Cardano devnet and deploy the registry contracts.
11.	Start three Java nodes and observe healthy PING/PONG connectivity.
12.	Create and administer a topic on-chain and observe identical registry state on all nodes.
13.	Allow nodes to bootstrap, peer-sample, navigate to the topic, and form same-topic dissemination links.
14.	Publish a signed event and observe delivery to all online topic subscribers with metrics.
15.	Persist the event on the deterministic R-node replica set.
16.	Take one subscriber offline, publish further events, reconnect it, and recover the exact missing events through the D2 indexing scheme.
17.	Demonstrate moderated-topic rejection of an unauthorized publisher.
18.	Export an experiment run bundle with configuration, seed, logs, and metric summary.
M9 replica repair and M10 larger-scale experiments are strongly recommended before a conference submission that makes resilience or performance claims.
20. Recommended first implementation sprint
The first sprint should deliberately avoid gossip algorithms. Its purpose is to establish the substrate on which every later component can be tested.
Order	Task	Acceptance evidence
1	Create Gradle multi-module skeleton, configuration model, structured logging, and test conventions.	Clean build/test command.
2	Create repeatable Cardano devnet scripts with funded test wallets/credentials.	Devnet health and balance checks.
3	Create node-model and transport modules.	NodeId/PeerDescriptor serialization tests.
4	Start three nodes from one Docker Compose file with static endpoints.	Three healthy processes and status output.
5	Implement PING/PONG, correlation IDs, RTT measurement, connection-loss detection, and reconnect.	10-minute soak + restart-one-node test.
6	Define Registry Adapter Java interface and in-memory fake implementation before the real contract.	Protocol code can depend on interface without Cardano details.
7	Implement Topic Registry contract and Cardano adapter.	D2 admin operation tests on devnet.

Only after this sprint passes should M3-M6 introduce event signatures, SecureCyclon, Vicinity navigation, and Hybrid Dissemination. This keeps failures attributable to one layer at a time and gives the paper a clear implementation narrative.
21. References
19.	Antonov, A., Kolyvas, E., and Voulgaris, S. Cardano Pub/Sub Framework - Design and Architecture. Deliverable D2, September 2024.
20.	Antonov, A. and Voulgaris, S. SecureCyclon: Dependable Peer Sampling. IEEE ICDCS 2023. (Referenced by D2; exact implementation details are external to D2.)
21.	Voulgaris, S. and Van Steen, M. Vicinity: A Pinch of Randomness Brings Out the Structure. Middleware 2013. (Referenced by D2.)
22.	Voulgaris, S. and van Steen, M. Hybrid Dissemination: Adding Determinism to Probabilistic Multicasting in Large-Scale P2P Systems. Middleware 2007. (Referenced by D2.)
Appendix A. Requirement priority convention
Priority	Meaning
Must	Required for the stated milestone/prototype definition of done.
Should	Expected for research quality or D2 fidelity, but may move one milestone later without blocking the base demo.
Could	Optional extension; not used as a normative requirement in v0.1.

