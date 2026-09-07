# Implementation Report

## Task

Implement `specs/phase-0.7.md`: the first end-to-end D2 Event Persistence
baseline, including Cardano replication-server registration, deterministic
256-bit DHT placement, replicated persistent event/topic-log storage, lookup,
durable subscriber delivery progress, offline recovery, acceptance automation,
and evidence capture.

## Outcome

Implemented Phase 0.7 as an independent persistence path beside Hybrid
Dissemination. The publishing node sends valid events through the Phase 0.6
overlay and, in parallel, queues one submission to any active replication entry
server. Receiving subscribers update delivery state but do not resubmit the
event to persistence. The entry server validates
the original signed envelope, computes its deterministic key and responsible
replicas, waits for every effective replica, and then advances the replicated
publisher topic log. Persistence failures are logged and do not invalidate live
delivery.

Pub/Sub nodes now persist per-topic/per-publisher delivery cursors. The recovery
endpoint supplies its latest durable topic delivery timestamp, reads only
publisher progress newer than that timestamp, fetches independent missing keys
with bounded concurrency, revalidates every returned envelope, delivers only a
contiguous per-publisher sequence in order, suppresses already delivered
events, and atomically advances state that survives node restart.

The Java implementation, Aiken contract, script syntax, JavaScript helpers, and
Compose model have been validated. The clean-devnet Phase 0.7 acceptance passed
end to end after archiving/stopping Phase 0.6. The run passed all 71 Gradle
tasks, funded Cardano, deployed both registry validators, confirmed three
on-chain replication registrations, exercised exact `R=2` event placement and
non-responsible entry lookup, recovered exactly three events published while
node 3 was offline, preserved inventories across a responsible-server restart,
and observed exactly four publisher-originated persistence requests.

## Completed

- Added shared persistence records and strict 256-bit value handling, including
  `ReplicationServerState`, `StoredEvent`, `PublisherProgress`,
  `DeliveryProgress`, and `RecoveryResult`.
- Implemented exact event-key encoding as 32-byte topic ID + 32-byte publisher
  key ID + unsigned/non-negative 64-bit big-endian sequence, plus topic-log keys.
- Implemented shortest cyclic 256-bit assignment, deterministic server-ID tie
  breaking, conflicting-membership rejection, and
  `effectiveR = min(replicationFactor, activeServerCount)`.
- Added the Aiken replication registry datum/action model and validator. Updates
  preserve server ID/operator/value, require the controlling Cardano signer,
  validate endpoint/commitment fields, and unregistration is an inactive
  tombstone transition.
- Added Cardano CLI operations that derive IDs from encoded payment VKeys,
  submit signed registration/update/unregistration transactions, query inline
  registry datums, and atomically reconstruct active membership for processes.
- Added a standalone replication-server application with public v1 event,
  lookup, topic-publisher, and health endpoints and separate non-recursive
  internal replica endpoints.
- Added bounded request/connection timeouts, response/request sizes, retries,
  deterministic entry/failover behavior, and acknowledgement after every
  required event and topic-log replica confirms.
- Added atomic filesystem storage under `events/`, `topic-logs/`, and
  `metadata/`; idempotent duplicate writes; earliest-retention preservation;
  restart reconstruction; injectable epoch time; and periodic expiration.
- Reused Phase 0.3 event ID, Ed25519 signature, topic-active, and publisher
  authorization rules before persistence. Replica ingestion validates again.
- Integrated one asynchronous persistence submission at original local publish;
  remote subscriber acceptance only updates delivery state. Phase 0.6
  dissemination selection and forwarding remain unchanged.
- Added durable node delivery state, bounded-concurrency lookup, per-publisher
  ordered recovery, gap-safe cursor advancement, fetched-event revalidation,
  duplicate suppression, `POST /v1/events/recover/{topicId}`, and recovery-state
  inspection.
- Added `GET /v1/topics/{topicId}/publishers?sinceTimestamp=...`; progress
  entries are returned only when `latestTimestamp` is newer than the supplied
  offline timestamp, while omission retains the complete-log behavior.
- Added all requested structured lifecycle, persistence, lookup, topic-log,
  recovery, and expiration events with contextual identifiers.
- Added three-node/three-replica config and Compose topology plus acceptance
  automation for on-chain membership, exact replica inventories,
  non-responsible lookup, an offline node, exact recovery, replica restart,
  and lower-layer health evidence.

## Files Changed

| Area | Files and purpose |
| --- | --- |
| Build/modules | `settings.gradle.kts`; `libs/persistence-api/build.gradle.kts`; `libs/persistence-core/build.gradle.kts`; `apps/replication-server/build.gradle.kts`; Pub/Sub dependencies. |
| Registry contract | `contracts/replication-registry/` Aiken types, validator, configuration, lockfile, and README. |
| Persistence API | `libs/persistence-api/` membership, key, stored-event, progress, and recovery contracts. |
| Persistence core | `libs/persistence-core/` DHT assignment, atomic registry/event stores, epoch provider, HTTP client, delivery state, and recovery service. |
| Replication server | `apps/replication-server/` configuration, service, v1/internal HTTP APIs, runtime maintenance, logging, Docker image, and tests. |
| Pub/Sub node | `NodeConfig`, loader, main wiring, `NodePersistenceRuntime`, `NodeEventService`, and `EventControlServer` persistence/recovery integration. |
| Registry operations | `scripts/replication/` Aiken build/export, Cardano deployment, ID derivation, registration/update, unregistration, and query/reconstruction. |
| Acceptance/ops | `ops/config/phase-0.7/`, `ops/infra/phase-0.7/compose.yaml`, `scripts/acceptance/phase-0.7.sh`, and `phase-0.7.mjs`. |
| Documentation | `README.md`, `documentation/scripts.md`, persistence module READMEs, and this report. |

## Validation

- `gradlew.bat -g .gradle-user-home test --no-daemon`: passed all modules,
  including both new persistence modules, replication server tests, and all
  existing Phase 0.1–0.6 tests.
- New tests cover fixed binary event keys and component separation; deterministic
  replication count and 256-bit wraparound; controller authorization,
  active/inactive filtering, and registry reconstruction; valid/invalid and
  unauthorized events; atomic restart persistence and epoch expiration;
  monotonic topic logs; responsible-server storage/lookup/idempotency; ordered
  missed-event recovery; duplicate suppression; and recovery-state restart.
- Bundled Aiken 1.1.23 `check --skip-tests` and `build`: passed; generated the
  replication registry Plutus blueprint, and the validator export helper found
  `replication_registry.replication_registry.spend`.
- `node --check scripts/replication/registry-data.mjs` and
  `node --check scripts/acceptance/phase-0.7.mjs`: passed.
- WSL `bash -n scripts/replication/*.sh scripts/acceptance/phase-0.7.sh`: passed.
- `docker compose -f ops/infra/phase-0.7/compose.yaml config --quiet`: passed.
- `git diff --check`: passed apart from Git's existing Windows LF-to-CRLF
  conversion notices.
- Full `scripts/acceptance/phase-0.7.sh`: passed. Evidence is captured under
  `implementation-reports/evidence/phase-0.7/`; `final-result.txt` records
  `Phase 0.7 acceptance passed`.
- The passing runtime evidence records three active on-chain replication
  registrations, exactly two responsible holders for every tested event, lookup
  through a non-responsible entry server, filtered publisher progress, exactly
  three ordered recovered events with no unavailable keys, identical replica
  inventories before/after restart, and a publisher persistence-request count
  of four for four publications.

## Notes

- DHT distance is the shortest unsigned numeric distance around the 256-bit
  ring: `min(abs(serverId-key), 2^256-abs(serverId-key))`. Equal distances are
  resolved by `serverId`, which makes assignment deterministic at both sides
  of the ring.
- Registration datums are authoritative on Cardano. `servers.json` is an atomic
  decoded UTxO reconstruction cache used for bounded local polling; registry
  scripts refresh it after mutations and on query.
- The original `EventEnvelope` object is stored unchanged inside retention
  metadata. Replica replays are idempotent and cannot extend the first accepted
  event's expiration.
- Recovery does not disseminate recovered events back into the live overlay.
  It is a local delivery path, and a missing/invalid sequence prevents later
  sequences for that publisher from advancing the durable cursor.
- The acceptance script leaves the Phase 0.7 containers running after success
  for inspection, while its clean-start step removes only the explicitly named
  Phase 0.7/devnet Compose volumes.
