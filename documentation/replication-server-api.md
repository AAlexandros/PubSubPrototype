# Replication server HTTP API

`ReplicationHttpAdapter` exposes the HTTP API for one replication server. Its
route prefixes identify the caller and purpose:

- `/v1/client/*` accepts publisher events and serves routed event and progress lookups.
- `/v1/replication/*` serves authenticated server-to-server replication, repair, and inventory operations.
- `/v1/ops/*` exposes health and maintenance status.

All paths below are relative to the server base URL, for example
`http://127.0.0.1:8101`. JSON request bodies are limited to 4 MiB. SHA-256 IDs
are 64-character hexadecimal strings; accepted values are normalized to
lowercase.

## Caller authentication

| Routes | Caller check |
| --- | --- |
| `/v1/client/*` | No separate HTTP caller authentication. Event submission verifies the publisher signature and checks the publisher against the active topic policy. |
| `/v1/replication/*` | Signed request from a currently registered replication server. |
| `/v1/ops/*` | No caller authentication. |

For protected routes, the receiver verifies the sender's signature, current
membership, intended receiver, HTTP method, raw path and query, body hash,
timestamp, and nonce. The request signature is rejected when it is older than
one minute or its nonce has already been used for that receiver. The
`ReplicaRequestFilter` enforces this before the endpoint runs.

## Client API — `/v1/client`

These routes are used by Pub/Sub nodes to store events and recover missed data.

| Method and path | Request | Success response |
| --- | --- | --- |
| `POST /v1/client/events` | JSON `EventEnvelope` | `201` with `StoredEvent`, after the event and its publisher-progress entry are replicated to their responsible server sets. |
| `GET /v1/client/events/{eventKey}?topicId={topicId}` | `eventKey` and `topicId` are required SHA-256 IDs. | `200` with a validated `StoredEvent`; `404` with `{"error":"event_not_found","eventKey":"..."}` if no valid copy is found. |
| `GET /v1/client/topics/{topicId}/publishers?sinceTimestamp={milliseconds}` | Active topic ID. `sinceTimestamp` is optional and non-negative. | `200` with a list of signed `PublisherProgress` entries. |

For the publisher-progress lookup, omitted `sinceTimestamp` defaults to zero.
Zero returns all publishers, including entries with timestamp zero. A positive
value returns entries whose `latestTimestamp` is strictly greater than it. An
unknown or inactive topic is rejected.

`POST /v1/client/events` authenticates the **publisher of the event**, not the
HTTP caller. Any client that can reach the route may relay a valid
publisher-signed event. The server acknowledges only after its persistence
operation has completed; it returns `503` if that operation fails.

## Replication API — `/v1/replication`

Every route in this section requires an authenticated active replication server.
Local reads and writes operate on the receiving server's store without routing.

| Method and path | Request | Success response |
| --- | --- | --- |
| `POST /v1/replication/events` | JSON `StoredEvent` | `201` with the stored record. The contained event is validated before writing. |
| `GET /v1/replication/events/{eventKey}` | Event key. | `200` with the local `StoredEvent`, or `404` if this server has no copy. |
| `GET /v1/replication/topics/{topicId}/publishers` | Topic ID. | `200` with this server's local list of signed `PublisherProgress` entries. |
| `POST /v1/replication/topics/{topicId}/publishers` | JSON `PublisherProgress`; its topic must match the path. | `200` with the accepted progress. The server verifies both the HTTP sender and publisher progress. |
| `POST /v1/replication/repair` | JSON `ReplicaRepairRequest`. | `202` with the accepted request. This means repair was queued; it does not mean repair has completed. |
| `GET /v1/replication/inventory` | No body. | `200` bounded `ReplicaInventory` with local event metadata and topic-log publisher entries. |

The inventory is used by peer maintenance to compare replica placement. It lists
at most 10,000 event records and 10,000 topic logs.

## Operations API — `/v1/ops`

These read routes do not require caller authentication.

| Method and path | Response |
| --- | --- |
| `GET /v1/ops/health` | `200` object containing `status`, `serverId`, the active `membership`, and local `eventKeys`. |
| `GET /v1/ops/maintenance/status` | `200` `MaintenanceStatus`: `serverId`, `membershipVersion`, `suspectedPeers`, `confirmedUnavailablePeers`, `repairQueue`, and `underReplicatedRecords`. |

Health and maintenance status expose operational state. Client event and
publisher-progress lookups are also readable without a caller signature.

## Request and response records

- `EventEnvelope` carries `protocolVersion`, `topicId`, Base64
  `publisherPublicKey`, `sequenceNumber`, `timestamp`, Base64 `payload`, event
  `signature`, `eventId`, and `progressSignature`.
- `StoredEvent` carries `eventKey`, `eventEnvelope`, `storedAt`,
  `topicRetentionPeriod`, `storedAtEpoch`, and `expiresAfterEpoch`.
- `PublisherProgress` carries `topicId`, publisher key information, latest
  sequence and timestamp, and the publisher's signature over that progress.
- `ReplicaRepairRequest` carries `recordType` (`EVENT` or `TOPIC_LOG`),
  `recordKey`, `topicId`, `sourceServerIds`, and `unavailableServerIds`.

The JSON field names are the Java record component names shown above. Responses
are JSON. Error responses have an `error` property. Invalid input normally
returns `400`; rejected replication authentication returns `403`; protected
request bodies over 4 MiB return `413`; missing routes return `404`; unsupported
methods return `405`; persistence failures return `503`; unexpected handler
failures return `500`.

Services still verify records fetched from other servers during lookup and
repair. These records enter through a separate receiving path. The service also
retains checks for calls initiated by local maintenance rather than an HTTP
route. Sequence and placement decisions remain with the services because they
depend on current state.

The local node CLI (`scripts/pubsub-node.mjs`) sends commands to
`NodeControlHttpAdapter`. Its loopback HTTP adapter is a separate API and keeps
its own `/v1/events/*` and related paths; the prefixes above apply to the
replication server.
