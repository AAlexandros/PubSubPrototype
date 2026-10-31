import fs from 'node:fs';
import path from 'node:path';
import {TESTBED_PATHS} from '../lib/contracts.mjs';
import {nativePath as native} from '../lib/paths.mjs';
import {loadSectionedYaml as config} from '../lib/simple-yaml.mjs';

const root = fs.realpathSync(native(process.argv[2] || process.cwd()));
const nodeCount = Number(process.argv[3] || process.env.PUBSUB_NODE_COUNT || 3);
const serverIds = (process.argv[4] || '').split(',').filter(Boolean);
const settings = config(path.join(root, ...TESTBED_PATHS.SETTINGS_FILE.split('/')));
if (!Number.isInteger(nodeCount) || nodeCount < 3 || nodeCount > 50) throw Error('nodeCount must be between 3 and 50');
if (serverIds.length !== 3 || serverIds.some(id => !/^[0-9a-f]{64}$/.test(id))) throw Error('three replication server IDs are required');
const runtime = path.join(root, '.tools', 'phase-0.9');
const configs = path.join(runtime, 'config');
fs.mkdirSync(configs, {recursive: true});
for (let index = 1; index <= nodeCount; index++) {
  fs.mkdirSync(path.join(runtime, 'logs', `node-${index}`), {recursive: true});
}

for (let index = 1; index <= nodeCount; index++) {
  const seedNode = index === 1 ? 2 : 1;
  const seed = `[{host: pubsub-node-${seedNode}, port: ${settings.transportPort}}]`;
  fs.writeFileSync(path.join(configs, `node-${index}.yaml`), `node:
  name: node-${index}
  listenHost: 0.0.0.0
  listenPort: ${settings.transportPort}
  identityPath: /data/identity
peers: ${seed}
transport: {pingIntervalMs: 3000, pingTimeoutMs: 2000, reconnectInitialMs: 500, reconnectMaxMs: 5000}
registry: {runtimeDir: /registry, replicationRuntimeDir: /replication-registry, signer: node-1, cliBackend: HOST, pollIntervalMs: ${settings.registry.pollIntervalMs}, maximumAgeMs: ${settings.registry.maximumAgeMs}}
control: {host: 0.0.0.0, port: ${settings.controlPort}}
peerSampling: {advertisedHost: pubsub-node-${index}, viewSize: ${settings.secureCyclon.viewSize}, swapLength: ${settings.secureCyclon.swapLength}, cycleIntervalMs: ${settings.secureCyclon.cycleIntervalMs}, ageThreshold: ${settings.secureCyclon.ageThreshold}, proofFanout: ${settings.secureCyclon.proofFanout}, randomSeed: ${9000 + index}}
navigation: {capacity: ${settings.navigation.capacity}, routingBase: ${settings.navigation.routingBase}, cycleIntervalMs: ${settings.navigation.cycleIntervalMs}, staleAfterMs: ${settings.navigation.staleAfterMs}, subscriptionsPath: /data/identity/subscriptions.json}
dissemination: {randomLinkCount: ${settings.dissemination.randomLinkCount}, cycleIntervalMs: ${settings.dissemination.cycleIntervalMs}, staleAfterMs: ${settings.dissemination.staleAfterMs}, randomSeed: ${19000 + index}}
persistence: {deliveryStatePath: /data/identity/recovery-state.json, connectionTimeoutMs: ${settings.persistence.connectionTimeoutMs}, requestTimeoutMs: ${settings.persistence.requestTimeoutMs}, retries: ${settings.persistence.retries}, recoveryConcurrency: ${settings.persistence.recoveryConcurrency}}
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
  requestSigningKeyPath: /request-keys/payment.skey
  requestVerificationKeyPath: /request-keys/payment.vkey
registration:
  operator: ${signers[index - 1]}
  cliBackend: DEVNET
  commitmentStartEpoch: 0
  commitmentEndEpoch: 100000
registry:
  replicationRuntimeDir: /replication-registry
  pollIntervalMs: ${settings.registry.pollIntervalMs}
  maximumAgeMs: ${settings.registry.maximumAgeMs}
  topicRegistryRuntimeDir: /registry
  topicRegistrySigner: ${signers[index - 1]}
  topicRegistryCliBackend: HOST
timing: {connectionTimeoutMs: 500, requestTimeoutMs: 1500, retries: 0, cleanupIntervalMs: 1000, failureProbeAttempts: 3, failureProbeTimeoutMs: 500, maintenanceIntervalMs: 500}
`);
}

let compose = `name: phase-0-9\nservices:\n`;
for (let index = 1; index <= 3; index++) compose += `  replication-server-${index}:
    build: {context: ../.., dockerfile: apps/replication-server/Dockerfile, args: {CARDANO_TESTNET_VERSION: "\${CARDANO_TESTNET_VERSION:-11.0.1}"}}
    command: [/config/server-${index}.yaml]
    environment: {CARDANO_NETWORK_MAGIC: "\${CARDANO_NETWORK_MAGIC:-42}", CARDANO_NODE_SOCKET_PATH: /node-socket/node1/sock, CARDANO_CLI_TIMEOUT_MS: "15000"}
    volumes:
      - {type: bind, source: ./config/server-${index}.yaml, target: /config/server-${index}.yaml, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/keys/${signers[index - 1]}, target: /request-keys, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/registry/deployment.env, target: /registry/deployment.env, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/replication-registry/deployment.env, target: /replication-registry/deployment.env, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/testnet/socket, target: /node-socket, read_only: true}
      - replication-server-${index}-data:/data
    ports: ["127.0.0.1:${settings.hostReplicationPortBase + index}:${settings.replicationPort}"]
`;
for (let index = 1; index <= nodeCount; index++) compose += `  pubsub-node-${index}:
    build: {context: ../.., dockerfile: apps/pubsub-node/Dockerfile, args: {CARDANO_TESTNET_VERSION: "\${CARDANO_TESTNET_VERSION:-11.0.1}"}}
    command: [/config/node-${index}.yaml]
    environment: {NODE_LOG_FILE: /logs/node.log, NODE_INIT_LOG_FILE: /logs/initialization.log, NODE_NETWORK_LOG_FILE: /logs/network.log, CARDANO_NETWORK_MAGIC: "\${CARDANO_NETWORK_MAGIC:-42}", CARDANO_NODE_SOCKET_PATH: /node-socket/node1/sock, CARDANO_CLI_TIMEOUT_MS: "15000"}
    volumes:
      - {type: bind, source: ./config/node-${index}.yaml, target: /config/node-${index}.yaml, read_only: true}
      - {type: bind, source: ./logs/node-${index}, target: /logs}
      - {type: bind, source: ../../ops/infra/devnet/runtime/registry/deployment.env, target: /registry/deployment.env, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/replication-registry/deployment.env, target: /replication-registry/deployment.env, read_only: true}
      - {type: bind, source: ../../ops/infra/devnet/runtime/testnet/socket, target: /node-socket, read_only: true}
      - pubsub-node-${index}-identity:/data/identity
    ports: ["127.0.0.1:${settings.hostTransportPortBase + index}:${settings.transportPort}", "127.0.0.1:${settings.hostControlPortBase + index}:${settings.controlPort}"]
`;
compose += 'volumes:\n';
for (let index = 1; index <= 3; index++) compose += `  replication-server-${index}-data:\n`;
for (let index = 1; index <= nodeCount; index++) compose += `  pubsub-node-${index}-identity:\n`;
fs.writeFileSync(path.join(runtime, 'compose.yaml'), compose);
fs.writeFileSync(path.join(runtime, 'topology.json'), JSON.stringify({nodeCount, replicationServerCount: 3, serverIds, settings}, null, 2) + '\n');
console.log(path.join(runtime, 'compose.yaml'));
