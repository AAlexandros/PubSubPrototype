# D2 Topic Registry Contract

Phase 0.2 exposes the Topic Registry through `registry-api` and `registry-cardano`.

The current contract directory records the validator boundary and deployment artifact
location used by the prototype adapter:

- One topic token identifies each topic state UTxO.
- Owner-only and owner-or-admin transitions are enforced by the adapter command layer.
- Deleted topics remain in the registry state as inactive tombstones.

`scripts/registry/build.sh` runs Aiken checks when `aiken` is available and always
runs the Java registry tests that exercise datum encoding, authorization, tombstones,
and cache rebuild behavior.
