# Pub/Sub node architecture

Arrows show calls or callbacks. Responses are omitted. Outgoing peer messages
pass through Peer Transport; incoming messages return through Transport Listener.
Each node queries Cardano independently and publishes local registry views.

```mermaid
flowchart TB
    LOCAL["Local Application"]
    ADMIN["Topic owner or administrator"]
    REGISTRY_TOOLS["Registry administration tools"]
    CARDANO["Cardano Topic and Replication Registries"]
    REPLICATION["Replication Servers"]
    PEERS["Remote Pub/Sub Nodes"]

    subgraph NODE["Pub/Sub Node"]
        direction TB

        REG["Local Topic and Membership Views"]
        SAMPLING["Peer Sampling"]
        NAV["Navigation"]
        DIS["Dissemination"]
        EVENT["Event Processing"]
        STORAGE["Event Storage"]
        TRANSPORT["Peer Transport"]
        LISTENER["Transport Listener"]

        NAV -->|"Read active topic IDs"| REG
        DIS -->|"Read active topic IDs"| REG
        EVENT -->|"Read topic policy"| REG
        STORAGE -->|"Read topic policy for recovery"| REG

        NAV -->|"Read peer sample"| SAMPLING
        DIS -->|"Read topic candidates"| NAV

        EVENT -->|"Forward accepted events"| DIS
        EVENT -->|"Save accepted progress; queue storage for local publications"| STORAGE
        STORAGE -->|"Recovery callback: validate fetched events"| EVENT

        SAMPLING -->|"Gossip"| TRANSPORT
        NAV -->|"Gossip"| TRANSPORT
        DIS -->|"Gossip / events"| TRANSPORT

        TRANSPORT -->|"Dispatch incoming messages"| LISTENER
        LISTENER -->|"Sampling messages and peer status"| SAMPLING
        LISTENER -->|"Navigation messages"| NAV
        LISTENER -->|"Dissemination messages"| DIS
        LISTENER -->|"Received events and validation results"| EVENT
    end

    LOCAL -->|"Publish"| EVENT
    LOCAL -->|"Subscribe or unsubscribe"| NAV
    LOCAL -->|"Subscribe or unsubscribe"| DIS
    LOCAL -->|"Request recovery"| STORAGE

    ADMIN -->|"Add or remove publisher"| REGISTRY_TOOLS
    REGISTRY_TOOLS -->|"Submit transaction and query confirmed state"| CARDANO
    REG -->|"Poll confirmed UTxOs through read-only socket"| CARDANO
    STORAGE -->|"Store events, query progress, fetch events"| REPLICATION
    TRANSPORT <-->|"P2P messages"| PEERS

    classDef internal fill:#e8f0fe,stroke:#4667a6,color:#172b4d
    classDef external fill:#f2e8fa,stroke:#8464a5,color:#3f285f

    class REG,SAMPLING,NAV,DIS,EVENT,STORAGE,TRANSPORT,LISTENER internal
    class LOCAL,ADMIN,REGISTRY_TOOLS,CARDANO,REPLICATION,PEERS external
```
