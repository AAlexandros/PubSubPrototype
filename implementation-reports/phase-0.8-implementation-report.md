# Implementation Report

## Task

Implement `specs/phase-0.8.md`: automatic replication-server failure
detection, decentralized event/topic-log repair, live membership migration,
safe replica release, persistent server rejoin, dynamic replication-factor
maintenance, operational APIs, four-server acceptance automation, and evidence
capture.

## Outcome

Implemented Phase 0.8 as a maintenance layer over the Phase 0.7 deterministic
placement and persistence path. Each replication server derives a stable
membership fingerprint, monitors only active peers that share responsibility
for one or more locally stored records, and requires the configured number of
consecutive health-probe failures before excluding a peer from placement.

Maintenance is decentralized. Servers exchange bounded record metadata, send
idempotent repair notifications containing the current membership snapshot,
computed responsible set, unavailable peers, and surviving sources, and the
newly responsible server pulls the record. Event replicas pass the existing
envelope validation before atomic storage; topic publisher logs merge without
decreasing sequence or same-sequence timestamp. Failed work remains queued for
later maintenance cycles.

Membership joins/leaves and topic replication-factor changes are observed
without restart. Existing holders drive migration, a joining/rejoining server
also performs its own inventory synchronization, and no-longer-responsible
copies are released only after every current target advertises the record under
the exact current membership fingerprint. A persisted server-ID marker prevents
a data volume from being restarted under a different identity.

The final clean acceptance run passed. It exercised threshold failure
detection, automatic repair, lookup during failure, offline-subscriber
recovery, rejoin, S4 live registration/migration, both `R=2 -> R=3` and
`R=3 -> R=2` convergence, and S3 unregistration followed by final migration.
An asynchronous-unregistration helper race found on the preceding run was
fixed by waiting until Cardano exposes the inactive tombstone and refreshing
the shared membership cache before returning.

## Completed

- Added configurable active probing with defaults of three attempts and a
  5000 ms probe timeout, consecutive-failure tracking, suspicion clearing, and
  confirmed recovery.
- Added deterministic membership snapshot fingerprints and propagated
  confirmed unavailability into new event/topic-log placement.
- Added bounded event and publisher-log inventories that carry placement and
  retention metadata without transferring event payloads.
- Added idempotent repair queues and internal pull-repair notifications with
  surviving sources, responsible server IDs, unavailable server IDs, reason,
  and membership version.
- Added validated event repair, monotonic topic-log merge, retryable failures,
  under-replication accounting, and all Phase 0.8 repair lifecycle logs.
- Added decentralized initial/join synchronization and current-placement-only
  rejoin behavior.
- Added safe excess-replica release for membership and replication-factor
  changes, guarded by target inventories from the same membership snapshot.
- Added persistent storage ownership through `metadata/server-id.txt` and a
  durable topic-log index used to reconstruct maintenance inventory after
  restart.
- Added `GET /v1/maintenance/status` and `GET /v1/maintenance/replicas`, while
  preserving all Phase 0.7 public endpoints.
- Added a four-replication-server Phase 0.8 Compose topology, three matching
  Pub/Sub node configs, complete acceptance/evidence automation, and clean
  archival/stopping of a running Phase 0.7 stack that owns the same host ports.
- Hardened server unregistration to wait for the submitted inactive Cardano
  datum before returning and before consumers use the reconstructed cache.
- Updated persistence and script documentation for Phase 0.8.

## Files Changed

| Area | Files and purpose |
| --- | --- |
| Persistence API | New event/topic-log inventory, repair request/status, record type, and maintenance status records under `libs/persistence-api/`. |
| Persistence core | `FileEventStore` inventory, deletion, monotonic merge, topic index, and identity persistence; membership fingerprints; health/inventory/repair HTTP client operations; timestamp-aware client progress merge. |
| Replication server | `PeerFailureDetector`, `ReplicaMaintenanceManager`, maintenance/repair HTTP routes, failure-aware placement, config defaults, and runtime scheduling. |
| Tests | Probe threshold/recovery, departed-peer cleanup, duplicate notification idempotency, monotonic topic-log behavior, safe release support, and persistent identity tests. |
| Registry operations | `scripts/replication/unregister-server.sh` now confirms the on-chain inactive tombstone and refreshes membership before success. |
| Acceptance/ops | `ops/config/phase-0.8/`, `ops/infra/phase-0.8/compose.yaml`, `scripts/acceptance/phase-0.8.sh`, and `phase-0.8.mjs`. |
| Documentation | `README.md`, persistence READMEs, `documentation/scripts.md`, and this report. |

## Validation

- `gradlew.bat -g .gradle-user-home test --no-daemon`: passed all 49 task
  executions in the complete multi-module test graph.
- `node --check scripts/acceptance/phase-0.8.mjs`: passed.
- `docker compose -f ops/infra/phase-0.8/compose.yaml config --quiet`: passed.
- WSL `bash -n scripts/replication/unregister-server.sh scripts/acceptance/phase-0.8.sh`:
  passed.
- `git diff --check`: passed apart from existing Windows LF-to-CRLF notices.
- Clean Cardano/Compose acceptance after the dissemination readiness fix:
  passed initial three-member placement, suspicion before confirmation,
  confirmed failure, under-replication evidence, event/topic-log repair,
  lookup during failure, two-event offline recovery, failed-server rejoin,
  live S4 membership and migration, five retained events at `R=3`, the topic
  log at `R=3`, and safe convergence of those records back to `R=2`.
- Final clean Cardano/Compose acceptance after hardening the unregistration
  helper: passed and recorded `Phase 0.8 acceptance passed`.
- Evidence audit: all 33 event-placement snapshots exactly match their
  expected responsible sets; factor-3, factor-2, and final topic-log holders
  converge with identical sequence/timestamp metadata; final maintenance
  inventories have no suspected or unavailable peers, queued repairs, or
  under-replicated records. Cardano evidence progresses from 3/3 active, to
  4/4 active after S4 joins, to 3/4 active after S3 is unregistered.
- Structured logs contain all required membership, failure, repair, release,
  factor-change, and join-sync event families, with no `REPAIR_FAILED` events.

## Notes

- Membership versions are SHA-256 fingerprints of the sorted active
  `serverId@host:port` snapshot. Release requires exact equality, a conservative
  form of the spec's "at least as recent" requirement that prevents destructive
  deletion under conflicting snapshots.
- Failure notifications propagate confirmed unavailable IDs so a replacement
  can accept and pull an assigned record without first monitoring or
  independently confirming the same failed peer. It begins monitoring that peer
  after the repaired record becomes local and clears the state on recovery.
- Temporary over-replication is retained until every desired holder's current
  inventory confirms the event or a publisher log at least as advanced as the
  local copy.
- The operational inventory is capped at 10,000 events and 10,000 topic logs
  per response for the prototype. Event payloads are fetched only by a server
  with queued responsibility.
