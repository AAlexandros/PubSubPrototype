const [action, countText, id, expected] = process.argv.slice(2);
const nodeCount = Number(countText);
if (!Number.isInteger(nodeCount) || nodeCount < 3) throw Error('node count must be at least 3');

const allEndpoints = [
  ...Array.from({length: nodeCount}, (_, i) => ({name: `pubsub-node-${i + 1}`, url: `http://127.0.0.1:${8001 + i}/v1/ops/registry`})),
  ...Array.from({length: 3}, (_, i) => ({name: `replication-server-${i + 1}`, url: `http://127.0.0.1:${8101 + i}/v1/ops/registry`}))
];
const endpoints = action === 'dissemination-ready'
  ? Array.from({length: nodeCount}, (_, i) => ({
    name: `pubsub-node-${i + 1}`,
    url: `http://127.0.0.1:${8001 + i}/v1/dissemination/view/${id}`
  }))
  : ['not-ready', 'not-loaded', 'stale'].includes(action) && id
    ? allEndpoints.filter(endpoint => id.split(',').includes(endpoint.name)) : allEndpoints;
if (endpoints.length === 0) throw Error('no matching endpoints');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function read(endpoint) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 3000);
  try {
    const response = await fetch(endpoint.url, {signal: controller.signal});
    if (!response.ok) throw Error(`${endpoint.name}: HTTP ${response.status}`);
    return {name: endpoint.name, ...await response.json()};
  } catch (cause) {
    if (controller.signal.aborted) throw Error(`${endpoint.name}: request timed out after 3000ms`);
    throw Error(`${endpoint.name}: ${cause.message}`);
  } finally {
    clearTimeout(timeout);
  }
}

function matches(state) {
  if (action === 'dissemination-ready') {
    const peers = [state.predecessor, state.successor, ...(state.randomPeers ?? [])];
    return state.topicId === id && peers.some(peer => peer?.nodeId && peer.nodeId !== state.nodeId);
  }
  if (action === 'ready') return state.topic === 'FRESH' && state.replication === 'FRESH';
  if (action === 'not-ready') return state.topic !== 'FRESH' || state.replication !== 'FRESH';
  if (action === 'not-loaded') return state.topic === 'NOT_LOADED' && state.replication === 'NOT_LOADED';
  if (action === 'stale') return state.topic === 'STALE' && state.replication === 'STALE';
  if (action === 'topic') {
    const topic = state.topics?.find(value => value.topicId?.value === id || value.topicId === id);
    if (expected === 'active') return topic?.active === true;
    if (expected === 'deleted') return topic?.active === false;
    if (expected?.startsWith('factor:')) return topic?.replicationFactor === Number(expected.slice(7));
    if (expected?.startsWith('publisher:')) return topic?.publishers?.includes(expected.slice(10));
    if (expected?.startsWith('no-publisher:')) return topic && !topic.publishers?.includes(expected.slice(13));
    throw Error(`unknown topic expectation: ${expected}`);
  }
  if (action === 'member') {
    const present = state.members?.some(value => value.serverId === id && value.active !== false);
    return expected === 'present' ? present : !present;
  }
  throw Error(`unknown action: ${action}`);
}

let observations;
let error;
const startedAt = Date.now();
for (let attempt = 0; attempt < 180; attempt++) {
  try {
    observations = await Promise.all(endpoints.map(read));
    if (observations.every(matches)) {
      process.stdout.write(JSON.stringify({action, id, expected, observedAt: new Date().toISOString(),
        observationLatencyMs: Date.now() - startedAt, observations}, null, 2) + '\n');
      process.exit(0);
    }
    error = 'observations did not converge';
  } catch (cause) {
    error = cause.message;
  }
  await sleep(1000);
}
throw Error(`timed out waiting for ${action}: ${error}\n${JSON.stringify(observations, null, 2)}`);
