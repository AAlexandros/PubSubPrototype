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
- `--force-broadcast` and `--tamper-signature` are test controls for publish
  behavior.
- `--url <base-url>` overrides the default local address, which is useful when
  the node uses a custom control port.

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
