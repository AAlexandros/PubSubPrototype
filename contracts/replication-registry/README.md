# D2 Replication Server Registry

Each registration is an inline-datum UTxO at the `replication_registry` validator.
The datum contains the permanent SHA-256 server ID, controlling payment key hash,
network endpoint, commitment epoch range, and active flag. Updates preserve the
server ID, operator, and value; the validator requires the controlling Cardano
signature. Unregistration is an irreversible transition to `active = false`.

The operational scripts build the Aiken validator and maintain the decoded UTxO
snapshot consumed by replication servers. That snapshot is a reconstruction cache,
not an authority: the validator and signed Cardano transactions are authoritative.
