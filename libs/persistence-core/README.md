# Persistence Core

This module contains both the persistence contracts and their default
implementation. The shared Phase 0.7/0.8 types cover replication membership,
deterministic event and topic-log keys, stored event records, publisher
progress, durable delivery progress, recovery results, bounded replica
inventories, repair notifications, and maintenance status.

Transport-independent Phase 0.7/0.8 mechanics: clockwise unsigned 256-bit DHT
assignment, atomic filesystem event/topic-log storage, injectable epoch time,
bounded replication HTTP calls, membership reconstruction, durable subscriber
cursors, bounded-concurrency missed-event recovery, persistent server identity,
monotonic repair merges, and deterministic membership snapshot versions.
