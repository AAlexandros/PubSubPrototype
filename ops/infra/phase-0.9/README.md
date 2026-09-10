# Phase 0.9 generated topology

Phase 0.9 uses `scripts/testbed/generate.mjs` to write the effective Compose
topology to `.tools/phase-0.9/compose.yaml`. Generation is intentional: local
E1 runs can select 3, 10, or 30 real Pub/Sub JVM containers while retaining
the same three replication servers and Cardano devnet.

The checked-in source configuration is `ops/config/phase-0.9/testbed.yaml`.
