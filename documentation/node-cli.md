# Pub/Sub node CLI

Use `scripts/pubsub-node.mjs` to send local commands to a running node and read
its state. The testbed publishes each node's command port on host loopback only
(`127.0.0.1:8000 + node number`). The node command endpoint does not use operator
authentication; do not publish these ports on an external interface.

```text
node scripts/pubsub-node.mjs <command> --node <1..50> [options]
```

## Commands

| Command | Required options | Purpose |
| --- | --- | --- |
| `publish` | `--topic <id> --payload <text>` | Sign and publish an event from the selected node. |
| `inject` | `--file <event.json>` | Submit a signed event envelope to the selected node. The file may contain the envelope directly or under an `envelope` property. |
| `subscribe` | `--topic <id>` | Subscribe the node to a topic. |
| `unsubscribe` | `--topic <id>` | Remove the node's topic subscription. |
| `recover` | `--topic <id>` | Recover stored events for a topic. |
| `status` | — | Show peer-sampling, navigation, subscriptions, and recovery state. |
| `publisher-key-id` | — | Show the node's event publisher key ID. |
| `peer-view` | — | Show the peer-sampling view. |
| `navigation-view` | — | Show the navigation view. |
| `subscriptions` | — | List current subscriptions. |
| `dissemination-view` | `--topic <id>` (optional) | Show dissemination views, for all topics or one topic. |
| `recovery-state` | — | Show persisted delivery/recovery state. |

Publishing options:

- `--base64` treats `--payload` as already Base64 encoded; otherwise the CLI
  encodes the text as UTF-8 before sending it.
- `--force-broadcast` and `--tamper-signature` are acceptance-test controls.
- `--url <base-url>` overrides the default local address, which is useful when
  the node uses a custom control port.

## Publication paths

Normal `publish` creates and stores a signed event, then validates it. If
accepted, the node records delivery locally, queues persistence, and sends
the event to peers selected for the topic. If no topic peers are known, the
dissemination target count is zero and no peer message is sent. If rejected,
the request fails.
The response contains the event envelope; it does not confirm peer receipt.

`--force-broadcast` sends the event to every currently connected peer, regardless
of topic selection. It also sends events rejected by local validation so acceptance
tests can check receiver rejection. A rejected event is not recorded as delivered
or queued for persistence locally. Broadcast does not connect to additional peers.
`--tamper-signature` corrupts the outgoing signature after the signed local copy
is stored; use it with `--force-broadcast` to test receiver validation.

`inject` resends a supplied envelope through normal topic dissemination without
local validation, delivery recording, or persistence. It is used to test replay
and duplicate handling.

In the logs, `EVENT_PUBLISH_IN` marks the request and records both test flags.
`EVENT_PUBLISHED` marks the decision to propagate, before peer transmission.
For a rejected forced broadcast, `EVENT_VALIDATED accepted=false` shows the
local result; the receiving node logs its own rejection.
Normal dissemination logs `EVENT_SENT` per selected peer; forced broadcast
does not pass through that service. Check `EVENT_RECEIVED` on another node to
confirm arrival. `EVENT_PUBLISH_OUT route=broadcast` identifies the forced path.

Examples:

```bash
node scripts/pubsub-node.mjs status --node 3
node scripts/pubsub-node.mjs subscribe --node 2 --topic <topic-id>
node scripts/pubsub-node.mjs publish --node 1 --topic <topic-id> --payload "hello"
node scripts/pubsub-node.mjs publish --node 1 --topic <topic-id> --payload <base64> --base64
node scripts/pubsub-node.mjs inject --node 2 --file event.json
```

The CLI is the supported local interface for users and shell scripts. The
node's HTTP routes are its adapter implementation. They do not replace the
Netty peer protocol, which nodes use to communicate with one another.
