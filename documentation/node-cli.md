# Pub/Sub node CLI

Use `scripts/pubsub-node.mjs` to send commands to a running node and read
its state. Pass `--url` with the node's control base URL. The endpoint does not
use operator authentication, so bind it to a trusted interface.

```text
node scripts/pubsub-node.mjs <command> (--url <control-base-url> | --node <1..50>) [options]
```

## Commands

| Command | Required options | Purpose |
| --- | --- | --- |
| `publish` | `--topic <id> --payload <text>` | Sign and publish an event from the selected node. |
| `inject` | `--file <event.json>` | Acceptance-test replay of an envelope, directly or under an `envelope` property. |
| `subscribe` | `--topic <id>` | Subscribe the node to a topic. |
| `unsubscribe` | `--topic <id>` | Remove the node's topic subscription. |
| `events` | none | List this node's local events. Optional `--topic`, `--publisher`, `--offset`, and `--limit` filter and paginate the result. |
| `event` | `--event <id>` or `--topic <id> --publisher <id> --sequence <n>` | Read one complete local envelope. |
| `catch-up-status` | none | Inspect automatic catch-up and unresolved gaps. |
| `recover` | `--topic <id>` | Diagnostic compatibility command; normal restart recovery is automatic. |
| `status` | — | Show peer-sampling, navigation, subscriptions, and recovery state. |
| `publisher-key-id` | — | Print the node's event publisher key ID as plain text for use in registry commands. |
| `peer-view` | — | Show the peer-sampling view. |
| `navigation-view` | — | Show the navigation view. |
| `subscriptions` | — | List current subscriptions. |
| `dissemination-view` | `--topic <id>` (optional) | Show dissemination views, for all topics or one topic. |
| `recovery-state` | — | Show persisted delivery/recovery state. |

Options:

- `--base64` treats `--payload` as already Base64 encoded; otherwise the CLI
  encodes the text as UTF-8 before sending it.
- `--url <base-url>` selects the configured control address.
- `--node <1..50>` is shorthand for a local node at `127.0.0.1:8000 + node number`.
- `--force-broadcast` and `--tamper-signature` are acceptance-test controls.

`publish` signs the event with the selected node's key, validates it against
the topic policy, and sends it to selected peers. Its response contains the
event envelope but does not confirm peer receipt or replication persistence.
`inject` relays an existing signed envelope; it does not record a local delivery.

Examples for a node bound to `127.0.0.1:8000`:

```bash
node scripts/pubsub-node.mjs status --url http://127.0.0.1:8000
node scripts/pubsub-node.mjs subscribe --url http://127.0.0.1:8000 --topic <topic-id>
node scripts/pubsub-node.mjs publish --url http://127.0.0.1:8000 --topic <topic-id> --payload "hello"
node scripts/pubsub-node.mjs publisher-key-id --url http://127.0.0.1:8000
```

The node's control HTTP API handles these commands; nodes use a separate peer
protocol to communicate with one another.

## Local subscriber history

`events` returns a bounded page (default 100, maximum 500) ordered by topic ID,
publisher key ID, then sequence number. Its response has `events`, `offset`,
`limit`, and `hasMore` fields. `event` returns the complete stored envelope,
including its Base64 payload; an absent local event returns 404. These commands
read only the selected node's persistent volume. The equivalent HTTP routes are
`GET /v1/events`, `GET /v1/events/{eventId}`, and
`GET /v1/topics/{topicId}/events`.

```bash
node scripts/pubsub-node.mjs events --url http://127.0.0.1:8000 --topic <topic-id> --limit 50
node scripts/pubsub-node.mjs event --url http://127.0.0.1:8000 --event <event-id>
node scripts/pubsub-node.mjs event --url http://127.0.0.1:8000 --topic <topic-id> --publisher <key-id> --sequence 5
node scripts/pubsub-node.mjs catch-up-status --url http://127.0.0.1:8000
```

After a restart, the node automatically checks restored active subscriptions
against replication publisher progress and stores recoverable missing events.
Local event files are retained without automatic pruning. Replication-server
topic retention still limits how long an offline node can recover a gap.
