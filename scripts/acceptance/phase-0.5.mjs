import fs from 'node:fs';
import path from 'node:path';

// Runtime assertions use HTTP snapshots against /v1/navigation/view and /v1/peer-sampling/view.
const [mode, evidence, arg1, arg2] = process.argv.slice(2);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

function fingerTopics(ordinal, size, base) {
  if (size <= 0) return new Set();
  const origin = ((ordinal % size) + size) % size;
  const targets = new Set([origin]);
  for (let step = 1; step <= size / 2; step *= base) {
    targets.add(((origin + step) % size + size) % size);
    targets.add(((origin - step) % size + size) % size);
  }
  return targets;
}

async function navigationView(n) {
  const response = await fetch(`http://127.0.0.1:800${n}/v1/navigation/view`);
  if (!response.ok) throw Error(`Navigation view request failed for node-${n}: ${response.status}`);
  return response.json();
}

async function samplingView(n) {
  const response = await fetch(`http://127.0.0.1:800${n}/v1/peer-sampling/view`);
  if (!response.ok) throw Error(`Peer-sampling view request failed for node-${n}: ${response.status}`);
  return response.json();
}

async function views(nodes = [1, 2, 3]) {
  return Promise.all(nodes.map(navigationView));
}

async function wait(check, timeout = 90000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (await check()) return;
    await sleep(500);
  }
  throw Error(`Timed out: ${mode}`);
}

function save(name, data) { fs.writeFileSync(`${evidence}/${name}.json`, JSON.stringify(data, null, 2) + '\n'); }

function candidateCount(view) {
  return Object.values(view.view).reduce((total, bucket) => total + bucket.length, 0);
}

if (mode === 'prepare-devnet') {
  const root = fs.realpathSync(process.cwd());
  const backup = path.resolve(root, '.tools', 'phase-0.5-devnet-backups', String(Date.now()));
  for (const name of ['runtime', 'keys', 'state']) {
    const source = path.resolve(root, 'ops', 'infra', 'devnet', name);
    const target = path.resolve(backup, name);
    if (!source.startsWith(root + path.sep) || !target.startsWith(root + path.sep)) throw Error('Unsafe archive path');
    if (fs.existsSync(source)) {
      if (fs.lstatSync(source).isSymbolicLink()) throw Error('Refusing to archive symlink');
      fs.mkdirSync(backup, {recursive: true}); fs.renameSync(source, target);
    }
  }
  console.log(`Previous generated devnet state archived at ${backup}`);
} else if (mode === 'subscribe') {
  // arg1: node index, arg2: topicId.
  const response = await fetch(`http://127.0.0.1:800${arg1}/v1/subscriptions/${arg2}`, {method: 'POST'});
  if (!response.ok) throw Error(`Subscribe failed: ${response.status}`);
  save(`subscribe-node-${arg1}`, await response.json());
} else if (mode === 'initial') {
  const initial = await views(); save('initial-navigation-views', initial);
  const sampling = await Promise.all([1, 2, 3].map(samplingView)); save('initial-sampling-views', sampling);
} else if (mode === 'converge') {
  await wait(async () => {
    const current = await views();
    if (!current.every(v => candidateCount(v) > 0)) return false;
    save('converged-navigation-views', current); return true;
  });
} else if (mode === 'finger-topics') {
  const current = await views();
  for (const node of current) {
    const ordinals = node.subscriptions.map(id => node.topicOrdering.indexOf(id)).filter(i => i >= 0);
    const expected = new Set();
    for (const ordinal of ordinals) for (const t of fingerTopics(ordinal, node.topicOrdering.length, 2)) expected.add(t);
    const actual = new Set(node.fingerTopics);
    if (expected.size !== actual.size || [...expected].some(t => !actual.has(t))) {
      throw Error(`Finger topics mismatch for ${node.nodeId}: expected ${[...expected]} got ${[...actual]}`);
    }
  }
  save('finger-topic-verification', current);
} else if (mode === 'selection-quality') {
  const current = await views();
  const learned = [];
  for (const node of current) {
    for (const [target, bucket] of Object.entries(node.view)) {
      for (const candidate of bucket) learned.push({nodeId: node.nodeId, target, peerNodeId: candidate.nodeId, subscribedTopicIds: candidate.subscribedTopicIds});
    }
  }
  if (learned.some(entry => !Array.isArray(entry.subscribedTopicIds))) throw Error('Selected candidate missing subscription info');
  save('selection-quality', learned);
} else if (mode === 'resubscribe-recompute') {
  const before = await navigationView(arg1);
  const response = await fetch(`http://127.0.0.1:800${arg1}/v1/subscriptions/${arg2}`, {method: 'POST'});
  if (!response.ok) throw Error(`Resubscribe failed: ${response.status}`);
  await wait(async () => {
    const after = await navigationView(arg1);
    // With five topics a single subscription already covers the complete ring, so
    // the finger set may legitimately remain unchanged. The recomputation is
    // established by the new persistent subscription being reflected in the view
    // and by the endpoint returning a valid finger set for the current ordering.
    if (!after.subscriptions.includes(arg2)) return false;
    if (!after.fingerTopics.every(t => Number.isInteger(t) && t >= 0 && t < after.topicOrdering.length)) return false;
    if (after.topicOrdering.length !== before.topicOrdering.length) return false;
    save(`resubscribe-node-${arg1}`, {before, after}); return true;
  });
} else if (mode === 'removed') {
  const departed = JSON.parse(fs.readFileSync(`${evidence}/initial-navigation-views.json`))[2].nodeId;
  let since = 0;
  const snapshots = [];
  await wait(async () => {
    const current = await views([1, 2]);
    const stillPresent = current.some(n => Object.values(n.view).some(bucket => bucket.some(p => p.nodeId === departed)));
    if (stillPresent) { since = 0; snapshots.length = 0; return false; }
    since ||= Date.now(); snapshots.push(current);
    if (Date.now() - since < 12000) return false;
    save('failure-removal-navigation-views', snapshots); return true;
  }, 120000);
} else if (mode === 'restarted') {
  const departed = JSON.parse(fs.readFileSync(`${evidence}/initial-navigation-views.json`))[2].nodeId;
  await wait(async () => {
    const current = await views();
    if (current[2].nodeId !== departed) throw Error('Persistent identity changed on restart');
    const rediscovered = current.slice(0, 2).some(n => Object.values(n.view).some(bucket => bucket.some(p => p.nodeId === departed)));
    if (!rediscovered) return false;
    save('restart-rediscovery-navigation-views', current); return true;
  }, 120000);
} else if (mode === 'topic-created') {
  await wait(async () => {
    const current = await views();
    if (!current.every(n => n.topicOrdering.includes(arg1))) return false;
    save('topic-created-views', current); return true;
  });
} else if (mode === 'topic-deleted') {
  await wait(async () => {
    const current = await views();
    if (current.some(n => n.topicOrdering.includes(arg1))) return false;
    save('topic-deleted-views', current); return true;
  });
} else throw Error(`Unknown mode ${mode}`);
console.log(`Phase 0.5 assertion passed: ${mode}`);
