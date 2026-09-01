use aiken/crypto.{sha2_256}
use aiken/collection/dict
use aiken/collection/list
use aiken/primitive/bytearray
use cardano/assets
use cardano/assets.{PolicyId}
use cardano/transaction.{InlineDatum, OutputReference, Transaction}
use pubsub/topic_registry/types.{
  AddAdmin,
  AddOwner,
  AddPublisher,
  DeleteTopic,
  MintAction,
  MintTopic,
  RemoveAdmin,
  RemoveOwner,
  RemovePublisher,
  SetReplicationFactor,
  SetRetentionPeriod,
  TopicAction,
  TopicState,
  VerificationKeyHash,
}

validator topic_policy {
  mint(redeemer: MintAction, policy_id: PolicyId, self: Transaction) {
    let MintTopic { creation_ref, token_name, topic_id } = redeemer
    expect [Pair(minted_name, quantity)] =
      self.mint
        |> assets.tokens(policy_id)
        |> dict.to_pairs()

    let consumes_creation_seed =
      list.any(self.inputs, fn(input) { input.output_reference == creation_ref })

    let expected_token_name =
      creation_ref.transaction_id
        |> bytearray.concat(bytearray.from_int_big_endian(creation_ref.output_index, 8))
        |> sha2_256

    and {
      consumes_creation_seed?,
      (quantity == 1)?,
      (minted_name == token_name)?,
      (token_name == expected_token_name)?,
      (topic_id == expected_token_name)?,
    }
  }

  else(_ctx) {
    fail
  }
}

validator topic_state {
  spend(datum: Option<TopicState>, redeemer: TopicAction, own_ref: OutputReference, self: Transaction) {
    expect Some(current) = datum
    expect Some(own_input) =
      list.find(self.inputs, fn(input) { input.output_reference == own_ref })

    expect Some(next_output) =
      list.find(
        self.outputs,
        fn(output) { output.address == own_input.output.address },
      )
    expect InlineDatum(next_data) = next_output.datum
    expect next: TopicState = next_data

    and {
      authorized(self.extra_signatories, current, redeemer)?,
      current.active?,
      (next.topic_id == current.topic_id)?,
      positive_values(next)?,
      valid_owner_set(next)?,
      irreversible_deletion(current, next, redeemer)?,
      token_preserved(own_input.output.value, next_output.value)?,
      valid_transition(current, next, redeemer)?,
    }
  }

  else(_ctx) {
    fail
  }
}

fn authorized(signers: List<VerificationKeyHash>, topic: TopicState, action: TopicAction) -> Bool {
  when action is {
    DeleteTopic -> has_owner_signature(signers, topic)
    AddOwner(_) -> has_owner_signature(signers, topic)
    RemoveOwner(_) -> has_owner_signature(signers, topic)
    AddAdmin(_) -> has_owner_signature(signers, topic)
    RemoveAdmin(_) -> has_owner_signature(signers, topic)
    AddPublisher(_) -> has_owner_or_admin_signature(signers, topic)
    RemovePublisher(_) -> has_owner_or_admin_signature(signers, topic)
    SetReplicationFactor(_) -> has_owner_or_admin_signature(signers, topic)
    SetRetentionPeriod(_) -> has_owner_or_admin_signature(signers, topic)
  }
}

fn has_owner_signature(signers: List<VerificationKeyHash>, topic: TopicState) -> Bool {
  list.any(topic.owners, fn(owner) { list.has(signers, owner) })
}

fn has_owner_or_admin_signature(signers: List<VerificationKeyHash>, topic: TopicState) -> Bool {
  has_owner_signature(signers, topic)
    || list.any(topic.admins, fn(admin) { list.has(signers, admin) })
}

fn positive_values(topic: TopicState) -> Bool {
  topic.replication_factor > 0 && topic.retention_period > 0
}

fn valid_owner_set(next: TopicState) -> Bool {
  list.length(next.owners) > 0
}

fn irreversible_deletion(current: TopicState, next: TopicState, action: TopicAction) -> Bool {
  when action is {
    DeleteTopic -> current.active && !next.active
    _ -> current.active && next.active
  }
}

fn token_preserved(input_value, output_value) -> Bool {
  input_value == output_value
}

fn valid_transition(current: TopicState, next: TopicState, action: TopicAction) -> Bool {
  when action is {
    DeleteTopic ->
      next == TopicState { ..current, active: False }
    AddOwner(owner) ->
      next == TopicState { ..current, owners: add_unique(current.owners, owner) }
    RemoveOwner(owner) ->
      next == TopicState { ..current, owners: remove(current.owners, owner) }
    AddAdmin(admin) ->
      next == TopicState { ..current, admins: add_unique(current.admins, admin) }
    RemoveAdmin(admin) ->
      next == TopicState { ..current, admins: remove(current.admins, admin) }
    AddPublisher(publisher) ->
      next == TopicState { ..current, publishers: add_unique(current.publishers, publisher) }
    RemovePublisher(publisher) ->
      next == TopicState { ..current, publishers: remove(current.publishers, publisher) }
    SetReplicationFactor(replication_factor) ->
      next == TopicState { ..current, replication_factor: replication_factor }
    SetRetentionPeriod(retention_period) ->
      next == TopicState { ..current, retention_period: retention_period }
  }
}

fn add_unique(items: List<a>, item: a) -> List<a> {
  if list.has(items, item) {
    items
  } else {
    [item, ..items]
  }
}

fn remove(items: List<a>, item: a) -> List<a> {
  list.filter(items, fn(existing) { existing != item })
}
