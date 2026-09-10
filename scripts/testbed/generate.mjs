import fs from 'node:fs';
import path from 'node:path';

const native = value => process.platform !== 'win32' ? value
  : /^\/mnt\/[a-zA-Z]\//.test(value) ? `${value[5].toUpperCase()}:/${value.slice(7)}`
  : /^\/[a-zA-Z]\//.test(value) ? `${value[1].toUpperCase()}:/${value.slice(3)}` : value;
const root = fs.realpathSync(native(process.argv[2] || process.cwd()));
const nodeCount = Number(process.argv[3] || process.env.PUBSUB_NODE_COUNT || 3);
const serverIds = (process.argv[4] || '').split(',').filter(Boolean);
function config(file) {
  const result = {};
  let section = null;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!raw.trim() || raw.trimStart().startsWith('#')) continue;
    const split = raw.indexOf(':');
    const key = raw.slice(0, split).trim();
    const value = raw.slice(split + 1).trim();
    if (!/^\s/.test(raw) && !value) { section = key; result[section] = {}; continue; }
    const parsed = /^-?\d+$/.test(value) ? Number(value) : value;
    if (/^\s/.test(raw) && section) result[section][key] = parsed;
    else { section = null; result[key] = parsed; }
  }
  return result;
}
const settings = config(path.join(root, 'ops', 'config', 'phase-0.9', 'testbed.yaml'));
if (!Number.isInteger(nodeCount) || nodeCount < 3 || nodeCount > 50) throw Error('nodeCount must be between 3 and 50');
if (serverIds.length !== 3 || serverIds.some(id => !/^[0-9a-f]{64}$/.test(id))) throw Error('three replication server IDs are required');
const runtime = path.join(root, '.tools', 'phase-0.9');
const configs = path.join(runtime, 'config');
fs.mkdirSync(configs, {recursive: true});

for (let index = 1; index <= nodeCount; index++) {
  const seed = index === 1 ? '[]' : `[{host: pubsub-node-1, port: ${settings.transportPort}}]`;
  fs.writeFileSync(path.join(configs, `node-${index}.yaml`), `node:
  name: node-${index}
  listenHost: 0.0.0.0
  listenPort: ${settings.transportPort}
  identityPath: /data/identity
peers: ${seed}
transport: {pingIntervalMs: 3000, pingTimeoutMs: 2000, reconnectInitialMs: 500, reconnectMaxMs: 5000}
registry: {enabled: true, runtimeDir: /registry, signer: node-1, pollIntervalMs: 1000}
control: {host: 0.0.0.0, port: ${settings.controlPort}}
peerSampling: {advertisedHost: pubsub-node-${index}, viewSize: ${settings.secureCyclon.viewSize}, swapLength: ${settings.secureCyclon.swapLength}, cycleIntervalMs: ${settings.secureCyclon.cycleIntervalMs}, ageThreshold: ${settings.secureCyclon.ageThreshold}, randomSeed: ${9000 + index}}
navigation: {capacity: ${settings.navigation.capacity}, routingBase: ${settings.navigation.routingBase}, cycleIntervalMs: ${settings.navigation.cycleIntervalMs}, staleAfterMs: ${settings.navigation.staleAfterMs}}
dissemination: {randomLinkCount: ${settings.dissemination.randomLinkCount}, cycleIntervalMs: ${settings.dissemination.cycleIntervalMs}, staleAfterMs: ${settings.dissemination.staleAfterMs}, randomSeed: ${19000 + index}}
persistence: {enabled: true, membershipPath: /replication-registry/servers.json, deliveryStatePath: /data/identity/recovery-state.json, connectionTimeoutMs: ${settings.persistence.connectionTimeoutMs}, requestTimeoutMs: ${settings.persistence.requestTimeoutMs}, retries: ${settings.persistence.retries}, recoveryConcurrency: ${settings.persistence.recoveryConcurrency}}
`);
}
const signers = ['node-1', 'node-2', 'node-3'];
for (let index = 1; index <= 3; index++) {
  fs.writeFileSync(path.join(configs, `server-${index}.yaml`), `server:
  serverId: ${serverIds[index - 1]}
  listenHost: 0.0.0.0
  advertisedHost: replication-server-${index}
  port: ${settings.replicationPort}
  storagePath: /data
registry:
  membershipPath: /replication-registry/servers.json
  pollIntervalMs: 500
  topicRegistryRuntimeDir: /registry
  topicRegistrySigner: ${signers[index - 1]}
timing: {connectionTimeoutMs: 500, requestTimeoutMs: 1500, retries: 0, cleanupIntervalMs: 1000, failureProbeAttempts: 3, failureProbeTimeoutMs: 500, maintenanceIntervalMs: 500}
epoch: {zeroTimeMs: 0, lengthMs: 432000000}
`);
}

let compose = `name: phase-0-9\nservices:\n`;
for (let index = 1; index <= 3; index++) compose += `  replication-server-${index}:
    build: {context: ../.., dockerfile: apps/replication-server/Dockerfile}
    command: [/config/server-${index}.yaml]
    volumes:
      - {type: bind, source: ./config/server-${index}.yaml, target: /config/server-${index}.yaml, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/registry, target: /registry, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/replication-registry, target: /replication-registry, read_only: true}
      - replication-server-${index}-data:/data
    ports: [\"127.0.0.1:${settings.hostReplicationPortBase + index}:${settings.replicationPort}\"]
`;
for (let index = 1; index <= nodeCount; index++) compose += `  pubsub-node-${index}:
    build: {context: ../.., dockerfile: apps/pubsub-node/Dockerfile}
    command: [/config/node-${index}.yaml]
    volumes:
      - {type: bind, source: ./config/node-${index}.yaml, target: /config/node-${index}.yaml, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/registry, target: /registry, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/replication-registry, target: /replication-registry, read_only: true}
      - pubsub-node-${index}-identity:/data/identity
    ports: [\"127.0.0.1:${settings.hostTransportPortBase + index}:${settings.transportPort}\", \"127.0.0.1:${settings.hostControlPortBase + index}:${settings.controlPort}\"]
`;
compose += 'volumes:\n';
for (let index = 1; index <= 3; index++) compose += `  replication-server-${index}-data:\n`;
for (let index = 1; index <= nodeCount; index++) compose += `  pubsub-node-${index}-identity:\n`;
fs.writeFileSync(path.join(runtime, 'compose.yaml'), compose);
fs.writeFileSync(path.join(runtime, 'topology.json'), JSON.stringify({nodeCount, replicationServerCount: 3, serverIds, settings}, null, 2) + '\n');
console.log(path.join(runtime, 'compose.yaml'));
