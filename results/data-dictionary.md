# Phase 0.9 telemetry data dictionary

Parquet is the canonical analysis format. JSONL files are the authoritative raw observations.

## `resource_samples`

Process/container resource observations.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `componentId` | string | identifier | Sampled node/server | no | experiment-controller |
| `cpuPercent` | double | percent | CPU utilization | yes | container runtime |
| `rssBytes` | long | bytes | Resident memory | yes | container runtime |
| `heapUsedBytes` | long | bytes | JVM heap in use | yes | JVM/runtime |
| `networkRxBytes` | long | bytes | Cumulative received bytes | yes | container runtime |
| `networkTxBytes` | long | bytes | Cumulative transmitted bytes | yes | container runtime |
| `diskReadBytes` | long | bytes | Cumulative disk reads | yes | container runtime |
| `diskWriteBytes` | long | bytes | Cumulative disk writes | yes | container runtime |
| `storedEventCount` | long | count | Stored events | yes | replication-server API |
| `storedEventBytes` | long | bytes | Stored event payload estimate | yes | replication-server API |
| `openConnections` | long | count | Active peer connections | yes | runtime API |

## `message_transmissions`

Actual protocol message sends.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `fromNodeId` | string | identifier | Sender | no | transport/persistence client |
| `toNodeId` | string | identifier | Receiver | no | transport/persistence client |
| `protocolLayer` | string | enum | TRANSPORT, SECURECYCLON, NAVIGATION, DISSEMINATION, or PERSISTENCE | no | runtime |
| `messageType` | string | enum | Wire/API message type | no | runtime |
| `topicId` | string | identifier | Topic when applicable | yes | runtime |
| `eventId` | string | identifier | Event when applicable | yes | runtime |
| `bytes` | long | bytes | Serialized transmission size | no | runtime |
| `success` | boolean | boolean | Transmission completed successfully | no | runtime |

## `cardano_transactions`

Local registry control-plane transactions.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `operation` | string | enum | Registry operation | no | experiment-controller |
| `registry` | string | enum | TOPIC or REPLICATION | no | experiment-controller |
| `transactionId` | string | identifier | Transaction hash when reported | yes | cardano-cli |
| `topicId` | string | identifier | Affected topic | yes | registry CLI |
| `serverId` | string | identifier | Affected replication server | yes | registry CLI |
| `durationMs` | double | milliseconds | CLI transaction latency | no | experiment-controller |
| `success` | boolean | boolean | Transaction confirmed | no | experiment-controller |
| `observationDelayMs` | double | milliseconds | Delay until runtime observation | yes | experiment-controller |

## `subscriptions`

Subscription changes and effective state.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `nodeId` | string | identifier | Subscriber node | no | pubsub-node API |
| `topicId` | string | identifier | Topic | no | pubsub-node API |
| `action` | string | enum | SUBSCRIBE, UNSUBSCRIBE, or SNAPSHOT | no | experiment-controller |
| `subscribed` | boolean | boolean | Effective state | no | pubsub-node API |

## `faults`

Explicit fault injections and recoveries.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `targetId` | string | identifier | Affected node/server | no | experiment-controller |
| `faultType` | string | enum | STOP, START, RESTART, MEMBERSHIP_CHANGE, or REPLICATION_FACTOR_CHANGE | no | scenario |
| `action` | string | enum | INJECTED or CLEARED | no | experiment-controller |
| `details` | string | JSON/text | Fault parameters | yes | scenario |

## `protocol_cycles`

Protocol and maintenance cycle observations.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `componentId` | string | identifier | Node or server running the cycle | no | runtime |
| `protocol` | string | enum | SECURECYCLON, NAVIGATION, DISSEMINATION, or REPLICA_MAINTENANCE | no | runtime |
| `cycleNumber` | long | count | Monotonic cycle index | no | experiment-controller |
| `durationMs` | double | milliseconds | Cycle observation/operation duration | yes | experiment-controller |
| `viewSize` | long | count | Observed view size | yes | runtime API |
| `success` | boolean | boolean | Cycle/API sample succeeded | no | experiment-controller |

## `event_lifecycle`

Event stage observations.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `nodeId` | string | identifier | Observing node | yes | pubsub-node |
| `topicId` | string | identifier | Topic | no | pubsub-node |
| `eventId` | string | identifier | Event content identifier | no | pubsub-node |
| `eventKey` | string | identifier | Persistence DHT key | yes | pubsub-node |
| `publisherKeyId` | string | identifier | Publisher signing-key identifier | yes | pubsub-node |
| `sequenceNumber` | long | count | Publisher sequence number | yes | pubsub-node |
| `peerNodeId` | string | identifier | Related peer | yes | pubsub-node |
| `stage` | string | enum | Lifecycle stage | no | pubsub-node |
| `reason` | string | text | Rejection or duplicate detail | yes | pubsub-node |

## `persistence_operations`

Persistence and recovery operations.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `nodeId` | string | identifier | Initiating node | yes | pubsub-node |
| `serverId` | string | identifier | Serving replication server | yes | replication-server |
| `operation` | string | enum | STORE, LOOKUP, RECOVERY_LOOKUP, REPAIR, RELEASE, or TOPIC_LOG_UPDATE | no | runtime |
| `topicId` | string | identifier | Topic | yes | runtime |
| `eventId` | string | identifier | Event | yes | runtime |
| `eventKey` | string | identifier | Persistence key | yes | runtime |
| `durationMs` | double | milliseconds | Operation latency | yes | runtime |
| `success` | boolean | boolean | Operation succeeded | no | runtime |
| `recordCount` | long | count | Records affected or recovered | yes | runtime |

## `replica_state`

Replica ownership and responsibility snapshots.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `serverId` | string | identifier | Server holding/reporting the record | no | replication-server API |
| `recordType` | string | enum | EVENT or TOPIC_LOG | no | replication-server API |
| `eventKey` | string | identifier | Event persistence key | yes | replication-server API |
| `topicId` | string | identifier | Topic | yes | replication-server API |
| `present` | boolean | boolean | Record is locally present | no | replication-server API |
| `responsible` | boolean | boolean | Server belongs to current responsible set | yes | experiment-controller |
| `membershipVersion` | string | hash | Replication membership fingerprint | yes | replication-server API |

## `runs`

One record per experiment run.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario identifier | no | scenario |
| `repetition` | long | count | Repetition index | no | scenario |
| `randomSeed` | long | seed | Deterministic run seed | no | scenario |
| `gitCommit` | string | git hash | Source revision | no | experiment-controller |
| `javaVersion` | string | version | Java runtime version | no | experiment-controller |
| `startTime` | string | ISO-8601 UTC | Run start | no | experiment-controller |
| `endTime` | string | ISO-8601 UTC | Run end | yes | experiment-controller |
| `protocolConfiguration` | string | JSON | Protocol settings | no | scenario |
| `workloadConfiguration` | string | JSON | Workload settings | no | scenario |
| `telemetryConfiguration` | string | JSON | Telemetry settings | no | scenario |
| `status` | string | enum | RUNNING, PASSED, or FAILED | no | experiment-controller |

## `overlay_edges`

Logical overlay snapshots.

| Field | Type | Unit | Meaning | Nullable | Source component |
| --- | --- | --- | --- | --- | --- |
| `runId` | string | identifier | Unique experiment run identifier | no | experiment-controller |
| `scenarioId` | string | identifier | Scenario family identifier | no | experiment-controller |
| `repetition` | long | count | Scenario repetition index | no | experiment-controller |
| `randomSeed` | long | seed | Deterministic run seed | no | experiment-controller |
| `timestampUtc` | string | ISO-8601 UTC | Wall-clock observation time | no | observing component |
| `monotonicTimeNs` | long | nanoseconds | Process monotonic observation time | no | observing component |
| `layer` | string | enum | SECURECYCLON, NAVIGATION, or DISSEMINATION | no | experiment-controller |
| `nodeId` | string | identifier | Local endpoint | no | runtime API |
| `peerNodeId` | string | identifier | Remote endpoint | no | runtime API |
| `topicId` | string | identifier | Topic for topic-specific overlays | yes | runtime API |
| `role` | string | enum | Edge role | yes | runtime API |
| `freshness` | long | milliseconds | Reported edge age | yes | runtime API |

