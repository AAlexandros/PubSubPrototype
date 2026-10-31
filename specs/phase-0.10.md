# Phase 0.10 — Direct Cardano Registry Observation

## Goal

Make Cardano the runtime source of truth for topic policies and replication-server membership. Each Pub/Sub node and replication server shall obtain its own registry view by querying the configured Cardano node. A registry change must become visible through polling without an administration command refreshing a shared file.

This phase closes a gap between the intended behavior in Phase 0.2.1 and the Phase 0.9 testbed, where running applications use `CACHE_ONLY` registry views mounted from a shared host directory.

## Scope

The phase covers reads of both on-chain registries by running applications:

- **Topic Registry:** topic existence and deletion, publisher allowlists, replication factor, retention, and any other topic settings used by the runtime.
- **Replication Registry:** active replication-server identities and contact endpoints used for storage, recovery, and replica placement.

The existing administration tools remain responsible for signing and submitting registry transactions. Subscriptions remain node and overlay state; subscribing does not write to Cardano.

This phase does not change the supported registry actions, put events on chain, or query Cardano for every event. The mint and spend handlers share one validator and script hash. Minting must place the new topic token directly at that validator, with a datum that satisfies the invariants expected by runtime readers. Mutations must preserve those invariants.

## Runtime behavior

### 1. Independent chain reads

1. Each Pub/Sub node shall poll the Topic Registry and Replication Registry through a configured Cardano query endpoint. Each replication server shall do the same for the registry state it consumes.
2. A successful poll shall reconstruct a complete registry view from the current on-chain UTxOs. The application shall validate that the UTxOs belong to the configured registry scripts and policy IDs and that their datums are well formed and internally consistent before publishing the new view to its services.
3. The application shall replace each in-memory registry view as a unit. Consumers must see either the previous valid view or the new valid view, never a partly parsed view.
4. A confirmed empty registry is a valid result. A failed, incomplete, or invalid query is an error and must never be interpreted as an empty registry.
5. Poll intervals shall be configurable. Each registry view shall poll immediately when its application starts and then at the configured interval. Polls shall not run once per event.

### 2. Local views and trust boundary

1. Every application instance shall own its registry view. If a disk snapshot is retained for diagnostics, it shall be stored per instance and written atomically after a successful chain query. A shared JSON file shall not be the runtime authority.
2. A cached file alone shall not authorize topic publishing, event acceptance, or membership-dependent storage after startup. The instance must complete its first successful chain read before becoming ready for those operations.
3. The expected network, registry script addresses, and policy IDs shall come from trusted deployment configuration. A query against the wrong network or unexpected registry identity shall fail visibly.
4. Runtime containers shall have read-only access to the Cardano query socket and the metadata needed to identify the registries. For this prototype, replication servers may also receive their registered Cardano payment signing key and reuse it to sign authenticated HTTP requests. This keeps the registered server identity and request-signing identity aligned and prioritizes implementation simplicity. Key material shall not be logged.
5. The trust claim is limited: querying through `cardano-cli` trusts the configured Cardano node and its socket. This phase does not implement independent cryptographic verification of the chain by the Java applications.

### 3. Freshness and failure handling

1. Each registry view shall track when it was last successfully read and, where available, the chain tip or slot observed with it. Observation freshness shall distinguish **not yet loaded**, **fresh**, and **stale/unavailable**.
2. A transient query failure shall retain the last valid view and report the failure. It shall not replace the view with empty state or partially parsed data.
3. A configurable maximum age shall bound how long a last valid view can be used for authorization and membership decisions. Once that age is exceeded, new operations that require current topic policy or server membership shall fail closed until a successful poll restores freshness. Existing stored data and process health diagnostics shall remain accessible where they do not require a new authorization decision.
4. A topic or publisher revocation can be observed only after a successful poll. The configured poll interval, query duration, and maximum age therefore define the system's revocation delay and outage behavior; these values shall be documented in the testbed configuration.
5. Query errors, invalid chain data, unexpected registry identity, and stale-view transitions shall produce distinct, actionable logs or metrics. Logs shall identify the registry, instance, poll result, view age, and observed chain position when available; they shall not expose signing material.

### 4. Consumers of registry state

- **Pub/Sub nodes:** registry sync updates the topic view used by navigation, dissemination, event validation, and recovery validation. The replication-server membership view supplies endpoints to event storage and recovery.
- **Replication servers:** the topic view controls event-policy checks and retention or replication settings. The membership view controls peer discovery, replica placement, and maintenance.
- All consumers of a given registry on one instance shall use the same published view. A policy change shall not require an application restart.

## Configuration and deployment

The local testbed shall run registry reads in a live query mode instead of `CACHE_ONLY`. It shall provide each Pub/Sub node with a working Cardano CLI or equivalent query client, a read-only node socket, the network identifier, and trusted registry deployment metadata. Replication servers already query Cardano for epoch information; their topic and membership registry reads shall also use the chain.

The Cardano command backend and configuration loader shall support those settings inside both application containers without depending on a host-only path layout. Administration scripts may continue to use their existing backend and signing identities. The prototype may mount the replication server's Cardano payment signing key into its runtime container for authenticated HTTP request signing, as described above. `CACHE_ONLY` may remain for explicit offline fixtures or tests, but it shall not be the generated integrated-testbed configuration.

Remove the shared registry cache mount as an authority for running applications. If administration tools continue to write snapshots for inspection, label them as diagnostic artifacts and ensure the applications do not consume them. Update the architecture documentation to show each application reading Cardano and maintaining its own local view.

## Deliverables

- Chain-backed topic and replication-registry readers wired into both running applications.
- Per-instance registry polling, atomic view replacement, readiness, and freshness behavior.
- Testbed image, socket mount, and configuration changes needed for read-only Cardano queries.
- Logs and metrics that show successful observations, failures, and stale views.
- Updated registry and deployment documentation, including the trust boundary and polling settings.
- An acceptance script and evidence under `scripts/acceptance/` and `implementation-reports/evidence/phase-0.10/`.

## Acceptance criteria

Run the integrated local devnet with at least three Pub/Sub nodes and three replication servers. Submit registry transactions without writing a shared cache during these checks:

1. An on-chain topic creation, policy update, publisher addition or removal, and deletion are observed by every Pub/Sub node and replication server that consumes topic state. Event authorization reflects the observed publisher list.
2. An on-chain replication-server registration or removal updates the membership used by Pub/Sub storage clients and replication-server maintenance.
3. With the applications using their direct Cardano registry views, an integrated persistence regression publishes events and checks replica placement, stops a responsible replication server, verifies event availability from surviving servers, restarts the failed server and verifies replica repair, then takes a subscriber offline and checks exact ordered missed-event recovery followed by ordinary live dissemination. The same scenario verifies the generated runtime configuration does not mount or select shared registry caches.
4. Restarting an application with no local registry snapshot rebuilds its views from Cardano. Deleting or tampering with a former shared cache file does not change its runtime view.
5. A process started while Cardano queries are unavailable does not become registry-ready. After a successful initial poll, a temporary outage retains the last valid view; exceeding the configured maximum age blocks new policy- or membership-dependent decisions. Recovery of chain access restores readiness.
6. Topic minting places the token at the same script that validates subsequent spends. Minting and mutation reject datums with duplicate actors within any one role list, malformed actor hash lengths, invalid UTF-8 topic names, or values outside the runtime-supported replication and retention ranges. Malformed, contradictory, or unexpected registry-token UTxOs cause a visible poll failure without publishing a partial or empty replacement view; unrelated UTxOs at the script address are ignored.
7. Observation latency and query load are recorded for the configured poll interval, including a scaled testbed run. The result shall show that polling is periodic and independent of event volume.

The evidence shall include the on-chain transaction or UTxO change, the observing applications' logs or metrics, the resulting behavior, and the configuration values used for polling and maximum age.
