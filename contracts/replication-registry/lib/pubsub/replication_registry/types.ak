use aiken/crypto.{Blake2b_224, Hash, VerificationKey}

pub type VerificationKeyHash =
  Hash<Blake2b_224, VerificationKey>

pub type ReplicationServerState {
  server_id: ByteArray,
  operator: VerificationKeyHash,
  host: ByteArray,
  port: Int,
  commitment_start_epoch: Int,
  commitment_end_epoch: Int,
  active: Bool,
}

pub type ReplicationServerAction {
  UpdateServer {
    host: ByteArray,
    port: Int,
    commitment_start_epoch: Int,
    commitment_end_epoch: Int,
  }
  UnregisterServer
}
