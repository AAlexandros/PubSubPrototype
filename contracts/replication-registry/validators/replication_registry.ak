use aiken/collection/list
use cardano/transaction.{InlineDatum, OutputReference, Transaction}
use pubsub/replication_registry/types.{
  ReplicationServerAction,
  ReplicationServerState,
  UnregisterServer,
  UpdateServer,
}

validator replication_registry {
  spend(
    datum: Option<ReplicationServerState>,
    action: ReplicationServerAction,
    own_ref: OutputReference,
    self: Transaction,
  ) {
    expect Some(current) = datum
    expect Some(input) = list.find(self.inputs, fn(candidate) {
      candidate.output_reference == own_ref
    })
    expect Some(output) = list.find(self.outputs, fn(candidate) {
      candidate.address == input.output.address
    })
    expect InlineDatum(next_data) = output.datum
    expect next: ReplicationServerState = next_data

    and {
      list.has(self.extra_signatories, current.operator)?,
      current.active?,
      (next.server_id == current.server_id)?,
      (next.operator == current.operator)?,
      (next.port > 0 && next.port <= 65535)?,
      (next.commitment_start_epoch >= 0)?,
      (next.commitment_end_epoch >= next.commitment_start_epoch)?,
      (input.output.value == output.value)?,
      valid_transition(current, next, action)?,
    }
  }

  else(_ctx) {
    fail
  }
}

fn valid_transition(
  current: ReplicationServerState,
  next: ReplicationServerState,
  action: ReplicationServerAction,
) -> Bool {
  when action is {
    UpdateServer { host, port, commitment_start_epoch, commitment_end_epoch } ->
      next == ReplicationServerState {
        ..current,
        host,
        port,
        commitment_start_epoch,
        commitment_end_epoch,
        active: True,
      }
    UnregisterServer -> next == ReplicationServerState { ..current, active: False }
  }
}
