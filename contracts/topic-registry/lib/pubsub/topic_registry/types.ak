use aiken/crypto.{Blake2b_224, Hash, VerificationKey}
use cardano/transaction.{OutputReference}

pub type VerificationKeyHash =
  Hash<Blake2b_224, VerificationKey>

pub type TopicState {
  topic_id: ByteArray,
  name: ByteArray,
  owners: List<VerificationKeyHash>,
  admins: List<VerificationKeyHash>,
  publishers: List<VerificationKeyHash>,
  replication_factor: Int,
  retention_period: Int,
  active: Bool,
}

pub type TopicAction {
  DeleteTopic
  AddOwner(VerificationKeyHash)
  RemoveOwner(VerificationKeyHash)
  AddAdmin(VerificationKeyHash)
  RemoveAdmin(VerificationKeyHash)
  AddPublisher(VerificationKeyHash)
  RemovePublisher(VerificationKeyHash)
  SetReplicationFactor(Int)
  SetRetentionPeriod(Int)
}

pub type MintAction {
  MintTopic {
    creation_ref: OutputReference,
    token_name: ByteArray,
    topic_id: ByteArray,
  }
}
