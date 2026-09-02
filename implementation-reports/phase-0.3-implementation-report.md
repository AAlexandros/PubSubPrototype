# Implementation Report

## Task

Implement `specs/phase-0.3.md`: add signed Pub/Sub event envelopes, deterministic event IDs, persistent publisher sequence numbers, registry-backed publisher authorization, duplicate/sequence conflict handling, baseline event dissemination, a local publish API, scripts, tests, and acceptance evidence/reporting.

## Outcome

Phase 0.3 implementation is in place at the Java/module/script level.

The prototype now has an `event-core` library for canonical event encoding, Ed25519 signing/verification, publisher key ID derivation, persistent per-topic/per-publisher sequence counters, and duplicate/sequence-conflict tracking. The framed protocol supports an `EVENT` message carrying a complete event envelope. Pub/Sub nodes expose a localhost control API, publish signed events using their persistent node identity, validate inbound events against the Cardano registry cache, forward first-accepted events to other peers, and suppress duplicates/loops.

The full Gradle build passes. The Phase 0.3 acceptance script exists at `scripts/acceptance/phase-0.3.sh`, and the matching full-phase wrapper now exists at `ops/scripts/acceptance/phase-0.3.sh`, consistent with Phase 0.2.

Phase 0.3 acceptance passed end to end. Earlier reruns exposed two infrastructure/integration issues along the way: the three-pool Docker testnet aborted with `node3 was unable to produce any blocks for 45s`, and publisher values were still being treated as Cardano identities in the registry transaction builder. The local devnet now uses one pool node, `wait-healthy.sh` has bounded tip queries plus fast failure when the Compose service exits, and the registry stores publishers as raw 256-bit event key IDs.

## Completed

- Added `libs:event-core`.
- Implemented immutable `EventEnvelope`.
- Implemented deterministic canonical binary event bodies.
- Implemented:
  - `signature = Ed25519.sign(canonicalEventBody)`
  - `eventId = SHA-256(canonicalEventBody)`
  - `publisherKeyId = SHA-256(encoded Ed25519 public key)`
- Implemented receiver-side event validation order:
  - topic lookup
  - active/inactive check
  - event ID recomputation
  - signature verification
  - publisher key ID derivation
  - open-topic or publisher-list authorization
  - duplicate/sequence conflict check
- Added persistent publisher sequence state under the node identity/runtime volume.
- Persisted produced event envelopes before network broadcast.
- Added startup recovery from persisted event filenames so sequence state advances even if the sequence file lags a previously persisted event.
- Added bounded duplicate/sequence tracking for `(topicId, publisherKeyId, sequenceNumber)`.
- Added `EVENT` to the protocol message enum and JSON codec validation.
- Added transport broadcast and forward APIs for event envelopes.
- Added event receive callback to `TransportListener`.
- Added node-side event service with structured logs:
  - `EVENT_PUBLISHED`
  - `EVENT_RECEIVED`
  - `EVENT_ACCEPTED`
  - `EVENT_FORWARDED`
  - `EVENT_DUPLICATE`
  - `EVENT_SEQUENCE_CONFLICT`
  - `EVENT_REJECTED`
- Added `EVENT_REJECTED` reasons:
  - `UNKNOWN_TOPIC`
  - `INACTIVE_TOPIC`
  - `INVALID_EVENT_ID`
  - `INVALID_SIGNATURE`
  - `UNAUTHORIZED_PUBLISHER`
  - `INVALID_SEQUENCE`
- Updated registry synchronization to retain tombstone topics for event validation.
- Updated Cardano registry transaction construction so `publishers[]` stores event publisher key IDs, while owners/admins remain Cardano payment key hashes.
- Added local node control endpoints:
  - `POST /v1/events/publish`
  - `POST /v1/events/inject` for acceptance-only duplicate replay
  - `GET /v1/events/publisher-key-id`
- Added `scripts/events/publish.sh`.
- Added `scripts/events/inject.sh`.
- Added `scripts/events/publisher-key-id.sh`.
- Updated `scripts/registry/add-publisher.sh` with node-name support for registering a running node's event-signing key ID.
- Added `scripts/acceptance/phase-0.3.sh`.
- Added `ops/scripts/acceptance/phase-0.3.sh`.
- Hardened `ops/infra/devnet/scripts/wait-healthy.sh`.
- Changed the local Cardano devnet to one pool node in `ops/infra/devnet/versions.env`.
- Fixed `scripts/events/inject.sh` to translate Git Bash `/c/...` paths for Windows Node before reading captured event envelopes.
- Updated node configs and Compose with localhost-mapped control ports.
- Normalized Pub/Sub container-internal ports so every node listens on `7000` for transport and `8000` for control, with Compose preserving distinct host mappings.
- Updated `documentation/scripts.md`.

## Files Changed

- `settings.gradle.kts`
- `libs/event-core/**`
- `libs/protocol-core/build.gradle.kts`
- `libs/protocol-core/src/main/java/org/pubsub/prototype/protocol/*`
- `libs/protocol-core/src/test/java/org/pubsub/prototype/protocol/ProtocolCodecTest.java`
- `libs/transport-netty/src/main/java/org/pubsub/prototype/transport/*`
- `libs/transport-netty/src/test/java/org/pubsub/prototype/transport/PubSubTransportIntegrationTest.java`
- `apps/pubsub-node/build.gradle.kts`
- `apps/pubsub-node/src/main/java/org/pubsub/prototype/node/*`
- `ops/config/phase-0.1/node-1.yaml`
- `ops/config/phase-0.1/node-2.yaml`
- `ops/config/phase-0.1/node-3.yaml`
- `ops/infra/devnet/scripts/wait-healthy.sh`
- `ops/infra/devnet/versions.env`
- `ops/infra/phase-0.1/compose.yaml`
- `libs/registry-cardano/src/main/java/org/pubsub/prototype/registry/cardano/AikenScriptDataCodec.java`
- `libs/registry-cardano/src/main/java/org/pubsub/prototype/registry/cardano/CardanoTransactionBuilder.java`
- `scripts/events/publish.sh`
- `scripts/events/inject.sh`
- `scripts/events/publisher-key-id.sh`
- `scripts/registry/add-publisher.sh`
- `scripts/acceptance/phase-0.3.sh`
- `ops/scripts/acceptance/phase-0.3.sh`
- `documentation/scripts.md`
- `implementation-reports/evidence/phase-0.3/*`

## Validation

Passed:

```powershell
$env:GRADLE_USER_HOME='C:\Users\AAntonov\Repositories\aueb\PubSubPrototype\.gradle-user-home'; .\gradlew.bat :libs:event-core:test :libs:protocol-core:test :libs:transport-netty:test :apps:pubsub-node:test
```

Passed:

```powershell
$env:GRADLE_USER_HOME='C:\Users\AAntonov\Repositories\aueb\PubSubPrototype\.gradle-user-home'; .\gradlew.bat clean build
```

Passed after the raw publisher key ID registry fix:

```powershell
$env:GRADLE_USER_HOME='C:\Users\AAntonov\Repositories\aueb\PubSubPrototype\.gradle-user-home'; .\gradlew.bat :libs:registry-cardano:test
```

Passed bash syntax checks under elevated Git Bash:

```powershell
& "C:\Program Files\Git\bin\bash.exe" -n scripts/events/publish.sh
& "C:\Program Files\Git\bin\bash.exe" -n scripts/events/inject.sh
& "C:\Program Files\Git\bin\bash.exe" -n scripts/events/publisher-key-id.sh
& "C:\Program Files\Git\bin\bash.exe" -n scripts/acceptance/phase-0.3.sh
```

Passed independent devnet readiness after changing the local devnet to one pool node:

```powershell
& "C:\Program Files\Git\bin\bash.exe" ./ops/infra/devnet/scripts/reset.sh
& "C:\Program Files\Git\bin\bash.exe" ./ops/infra/devnet/scripts/start.sh
& "C:\Program Files\Git\bin\bash.exe" ./ops/infra/devnet/scripts/wait-healthy.sh
```

Passed end to end, with evidence captured in `implementation-reports/evidence/phase-0.3/acceptance-output.log`:

```powershell
& "C:\Program Files\Git\bin\bash.exe" ./scripts/acceptance/phase-0.3.sh
```

The acceptance log records the full flow:

- Cardano devnet reset/start/health.
- Identity funding.
- Topic Registry build/deploy.
- Three Pub/Sub nodes start and sync registry state.
- Moderated topic creation.
- Registration of `node-1`'s event publisher key ID.
- Publish sequence `0` and verify acceptance on peers.
- Publish sequence `1`.
- Broadcast unregistered `node-2` event and verify rejection.
- Broadcast tampered-signature event and verify rejection.
- Replay accepted event and verify duplicate suppression.
- Restart `node-1` and verify next sequence is `3`.
- Create open topic.
- Publish from `node-2` on the open topic.
- Delete open topic.
- Broadcast event on deleted topic and verify rejection.
- Verify nodes continue PING/PONG operation.
- Tear down Pub/Sub containers and devnet.
- Finish with `Phase 0.3 acceptance passed`.

Key artifacts:

- `acceptance-output.log`
- `final-result.txt`
- `registered-publisher-key-id.txt`
- `published-event-sequence-0.json`
- `published-event-sequence-1.json`
- `unauthorized-publisher-event.json`
- `tampered-signature-event.json`
- `duplicate-resend.json`
- `sequence-continuity-after-restart.json`
- `open-topic-publication.json`
- `deleted-topic-rejected-event.json`
- `pubsub-node-1-final.log`
- `pubsub-node-2-final.log`
- `pubsub-node-3-final.log`
- `final-registry-snapshot-with-tombstones.json`

```text
implementation-reports/evidence/phase-0.3/
```

## Notes

- The normal publish path validates locally before broadcast.
- `--force-broadcast`, `--tamper-signature`, and `/v1/events/inject` are intentionally acceptance-only mechanisms for receiver-side negative-path testing.
- `publisherKeyId` registration depends on the target Pub/Sub node already running, because the key ID is read from that node's persistent Ed25519 identity through its local control API.
- The Phase 0.3 acceptance script passed from the captured run.
