#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import {nativePath} from './lib/paths.mjs';

const usage = `Usage:
  node scripts/pubsub-node.mjs <command> --node <1..50> [options]
    [--url http://127.0.0.1:<control-port>]

Commands:
  publish          --topic <id> --payload <text> [--base64] [--force-broadcast] [--tamper-signature]
  inject           --file <event.json>
  subscribe        --topic <id>
  unsubscribe       --topic <id>
  recover           --topic <id>
  publisher-key-id
  status
  peer-view
  navigation-view
  subscriptions
  dissemination-view [--topic <id>]
  recovery-state

Examples:
  node scripts/pubsub-node.mjs subscribe --node 2 --topic <topic-id>
  node scripts/pubsub-node.mjs publish --node 1 --topic <topic-id> --payload "hello"
  node scripts/pubsub-node.mjs peer-view --node 2`;

const [command, ...args] = process.argv.slice(2);
if (!command || command === '--help' || command === '-h') {
  console.log(usage);
  process.exit(command ? 0 : 2);
}

const values = new Map();
const flags = new Set();
for (let i = 0; i < args.length; i++) {
  const arg = args[i];
  if (!arg.startsWith('--')) throw Error(`Unexpected argument: ${arg}\n\n${usage}`);
  if (['--base64', '--force-broadcast', '--tamper-signature'].includes(arg)) {
    flags.add(arg);
  } else {
    if (i + 1 >= args.length || args[i + 1].startsWith('--')) throw Error(`${arg} requires a value`);
    values.set(arg, args[++i]);
  }
}

const node = Number(values.get('--node'));
if (!Number.isInteger(node) || node < 1 || node > 50) throw Error('--node must be between 1 and 50');
const baseUrl = values.get('--url') || `http://127.0.0.1:${8000 + node}`;
const topic = values.get('--topic');
const endpoint = {
  publish: ['/v1/events/publish', 'POST'],
  inject: ['/v1/events/inject', 'POST'],
  subscribe: ['/v1/subscriptions', 'POST'],
  unsubscribe: ['/v1/subscriptions', 'DELETE'],
  recover: ['/v1/events/recover', 'POST'],
  'publisher-key-id': ['/v1/events/publisher-key-id', 'GET'],
  status: ['/v1/peer-sampling/view', 'GET'],
  'peer-view': ['/v1/peer-sampling/view', 'GET'],
  'navigation-view': ['/v1/navigation/view', 'GET'],
  subscriptions: ['/v1/subscriptions', 'GET'],
  'dissemination-view': ['/v1/dissemination/view', 'GET'],
  'recovery-state': ['/v1/events/recovery-state', 'GET']
}[command];
if (!endpoint) throw Error(`Unknown command: ${command}\n\n${usage}`);

let [route, method] = endpoint;
let body;
if (['publish', 'subscribe', 'unsubscribe', 'recover'].includes(command) && !topic)
  throw Error(`${command} requires --topic`);
if (command === 'publish') {
  const payload = values.get('--payload');
  if (payload === undefined) throw Error('publish requires --payload');
  const encodedPayload = flags.has('--base64') ? payload : Buffer.from(payload, 'utf8').toString('base64');
  body = JSON.stringify({topicId: topic, payload: encodedPayload});
  const query = new URLSearchParams({
    forceBroadcast: String(flags.has('--force-broadcast')),
    tamperSignature: String(flags.has('--tamper-signature'))
  });
  route += `?${query}`;
} else if (command === 'inject') {
  const file = values.get('--file');
  if (!file) throw Error('inject requires --file');
  const event = JSON.parse(fs.readFileSync(path.resolve(nativePath(file)), 'utf8'));
  body = JSON.stringify(event.envelope || event);
} else if (command === 'subscribe' || command === 'unsubscribe' || command === 'recover') {
  route += `/${encodeURIComponent(topic)}`;
} else if (command === 'dissemination-view' && topic) {
  route += `/${encodeURIComponent(topic)}`;
}

const response = await fetch(new URL(route, `${baseUrl.replace(/\/$/, '')}/`), {
  method,
  ...(body === undefined ? {} : {headers: {'content-type': 'application/json'}, body})
});
const responseBody = await response.text();
if (!response.ok) throw Error(`${command} on node ${node} failed (${response.status}): ${responseBody}`);
if (responseBody) {
  const parsed = JSON.parse(responseBody);
  if (command === 'status') {
    const [navigation, subscriptions, recovery] = await Promise.all([
      fetch(new URL('/v1/navigation/view', `${baseUrl.replace(/\/$/, '')}/`)),
      fetch(new URL('/v1/subscriptions', `${baseUrl.replace(/\/$/, '')}/`)),
      fetch(new URL('/v1/events/recovery-state', `${baseUrl.replace(/\/$/, '')}/`))
    ]);
    for (const extra of [navigation, subscriptions, recovery]) {
      if (!extra.ok) throw Error(`status on node ${node} failed (${extra.status})`);
    }
    const [navigationView, subscriptionView, recoveryState] = await Promise.all([
      navigation.json(), subscriptions.json(), recovery.json()
    ]);
    process.stdout.write(JSON.stringify({peerSampling: parsed, navigation: navigationView,
      subscriptions: subscriptionView, recoveryState}, null, 2) + '\n');
  } else {
    process.stdout.write(JSON.stringify(parsed, null, 2) + '\n');
  }
}
