import fs from 'node:fs';
import path from 'node:path';

const [mode, evidence, arg1, arg2] = process.argv.slice(2);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const save = (name, data) => fs.writeFileSync(`${evidence}/${name}.json`, JSON.stringify(data, null, 2) + '\n');

async function json(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) throw Error(`${url} returned ${response.status}`);
  return response.json();
}
const dissemination = (node, topic) => json(`http://127.0.0.1:800${node}/v1/dissemination/view/${topic}`);
const navigation = node => json(`http://127.0.0.1:800${node}/v1/navigation/view`);

async function wait(check, timeout = 120000) {
  const end = Date.now() + timeout;
  let last;
  while (Date.now() < end) {
    try { if (await check()) return; } catch (error) { last = error; }
    await sleep(500);
  }
  throw Error(`Timed out in ${mode}${last ? `: ${last.message}` : ''}`);
}

function verifyRing(views) {
  const ordered = [...views].sort((a, b) => a.nodeId.localeCompare(b.nodeId));
  const allowed = new Set(ordered.map(view => view.nodeId));
  for (let i = 0; i < ordered.length; i++) {
    const view = ordered[i];
    const predecessor = ordered[(i - 1 + ordered.length) % ordered.length].nodeId;
    const successor = ordered[(i + 1) % ordered.length].nodeId;
    if (view.predecessor?.nodeId !== predecessor || view.successor?.nodeId !== successor) return false;
    if (view.randomPeers.length !== 1) return false;
    if (view.randomPeers.some(peer => peer.nodeId === view.nodeId || !allowed.has(peer.nodeId)
      || !peer.subscribedTopicIds.includes(view.topicId))) return false;
  }
  return true;
}

if (mode === 'prepare-devnet') {
  const root = fs.realpathSync(process.cwd());
  const backup = path.resolve(root, '.tools', 'phase-0.6-devnet-backups', String(Date.now()));
  for (const name of ['runtime', 'keys', 'state']) {
    const source = path.resolve(root, 'ops', 'infra', 'devnet', name);
    const target = path.resolve(backup, name);
    if (!source.startsWith(root + path.sep) || !target.startsWith(root + path.sep)) throw Error('Unsafe archive path');
    if (fs.existsSync(source)) {
      if (fs.lstatSync(source).isSymbolicLink()) throw Error('Refusing to archive symlink');
      fs.mkdirSync(backup, {recursive: true});
      fs.renameSync(source, target);
    }
  }
} else if (mode === 'subscribe') {
  const result = await json(`http://127.0.0.1:800${arg1}/v1/subscriptions/${arg2}`, {method: 'POST'});
  save(`subscribe-node-${arg1}-${arg2.slice(0, 8)}`, result);
} else if (mode === 'navigation-candidates') {
  await wait(async () => {
    const views = await Promise.all([1, 2, 3].map(navigation));
    const sameTopic = views.map(view => ({
      nodeId: view.nodeId,
      candidates: Object.values(view.view).flat().filter(peer => peer.subscribedTopicIds.includes(arg1))
    }));
    if (!sameTopic.every(entry => new Set(entry.candidates.map(peer => peer.nodeId)).size >= 2)) return false;
    save('navigation-same-topic-candidates', sameTopic);
    return true;
  });
} else if (mode === 'initial') {
  const views = await Promise.all([1, 2, 3].map(n => dissemination(n, arg1)));
  save('initial-dissemination-views', views);
} else if (mode === 'converged') {
  await wait(async () => {
    const views = await Promise.all([1, 2, 3].map(n => dissemination(n, arg1)));
    if (!verifyRing(views)) return false;
    save(arg2 || 'converged-dissemination-views', views);
    save('node-id-cyclic-ordering', views.map(v => v.nodeId).sort());
    save('predecessor-successor-verification', views.map(view => ({
      nodeId: view.nodeId,
      predecessor: view.predecessor.nodeId,
      successor: view.successor.nodeId
    })));
    save('random-link-evidence', views.map(view => ({nodeId: view.nodeId, randomPeers: view.randomPeers})));
    return true;
  });
} else if (mode === 'repair') {
  await wait(async () => {
    const views = await Promise.all([1, 2].map(n => dissemination(n, arg1)));
    if (!verifyRing(views)) return false;
    save('failure-repair-views', views);
    return true;
  });
} else if (mode === 'rejoin') {
  const initial = JSON.parse(fs.readFileSync(`${evidence}/converged-dissemination-views.json`, 'utf8'));
  const expectedNodeThree = initial[2].nodeId;
  await wait(async () => {
    const views = await Promise.all([1, 2, 3].map(n => dissemination(n, arg1)));
    if (views[2].nodeId !== expectedNodeThree || !verifyRing(views)) return false;
    save('restart-rejoin-views', views);
    return true;
  });
} else if (mode === 'second-topic') {
  await wait(async () => {
    const views = await Promise.all([1, 2].map(n => dissemination(n, arg1)));
    if (!verifyRing(views)) return false;
    const nonSubscriber = await fetch(`http://127.0.0.1:8003/v1/dissemination/view/${arg1}`);
    if (nonSubscriber.status !== 404) return false;
    save('second-topic-isolation-views', {subscribers: views, nonSubscriberStatus: nonSubscriber.status});
    return true;
  });
} else {
  throw Error(`Unknown mode: ${mode}`);
}
