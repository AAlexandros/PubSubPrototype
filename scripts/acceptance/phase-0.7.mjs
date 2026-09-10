import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {nativePath as native} from '../lib/paths.mjs';

const [mode, rawEvidence, ...args] = process.argv.slice(2);
const evidence = native(rawEvidence);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const save = (name, value) => fs.writeFileSync(path.join(evidence, `${name}.json`), JSON.stringify(value, null, 2) + '\n');
async function json(url, options) {
  const response = await fetch(url, options);
  const text = await response.text();
  if (!response.ok) throw Error(`${url} returned ${response.status}: ${text}`);
  return JSON.parse(text);
}
async function wait(check, timeout = 120000) {
  const end = Date.now() + timeout;
  let last;
  while (Date.now() < end) {
    try { const result = await check(); if (result) return result; } catch (error) { last = error; }
    await sleep(500);
  }
  throw Error(`Timed out in ${mode}${last ? `: ${last.message}` : ''}`);
}
const health = n => json(`http://127.0.0.1:810${n}/v1/health`);
const serverIndex = id => args[0].split(',').indexOf(id) + 1;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
function eventKey(envelope) {
  const publisher = sha(Buffer.from(envelope.publisherPublicKey, 'base64'));
  const sequence = Buffer.alloc(8);
  sequence.writeBigUInt64BE(BigInt(envelope.sequenceNumber));
  return {eventKey: sha(Buffer.concat([Buffer.from(envelope.topicId, 'hex'), Buffer.from(publisher, 'hex'), sequence])),
    publisherKeyId: publisher};
}
function responsible(key, membership, factor) {
  const ring = 1n << 256n;
  const numeric = BigInt(`0x${key}`);
  return [...membership].sort((a, b) => {
    const directA = BigInt(`0x${a.serverId}`) > numeric ? BigInt(`0x${a.serverId}`) - numeric : numeric - BigInt(`0x${a.serverId}`);
    const directB = BigInt(`0x${b.serverId}`) > numeric ? BigInt(`0x${b.serverId}`) - numeric : numeric - BigInt(`0x${b.serverId}`);
    const da = directA < ring - directA ? directA : ring - directA;
    const db = directB < ring - directB ? directB : ring - directB;
    return da < db ? -1 : da > db ? 1 : a.serverId.localeCompare(b.serverId);
  }).slice(0, Math.min(factor, membership.length));
}

if (mode === 'prepare-devnet') {
  const root = fs.realpathSync(process.cwd());
  const backup = path.resolve(root, '.tools', 'phase-0.7-devnet-backups', String(Date.now()));
  for (const name of ['runtime', 'keys', 'state']) {
    const source = path.resolve(root, 'ops', 'infra', 'devnet', name);
    if (fs.existsSync(source)) {
      if (fs.lstatSync(source).isSymbolicLink()) throw Error('refusing to archive a devnet symlink');
      fs.mkdirSync(backup, {recursive: true});
      fs.renameSync(source, path.resolve(backup, name));
    }
  }
} else if (mode === 'server-configs') {
  const [rawRuntime, ids, epochZero = '0', epochLength = '432000000'] = args;
  const runtime = native(rawRuntime);
  fs.mkdirSync(runtime, {recursive: true});
  ids.split(',').forEach((id, index) => fs.writeFileSync(path.join(runtime, `server-${index + 1}.yaml`), `server:
  serverId: ${id}
  listenHost: 0.0.0.0
  advertisedHost: replication-server-${index + 1}
  port: 8100
  storagePath: /data
registry:
  membershipPath: /replication-registry/servers.json
  pollIntervalMs: 500
  topicRegistryRuntimeDir: /registry
  topicRegistrySigner: node-${index + 1}
timing:
  connectionTimeoutMs: 1000
  requestTimeoutMs: 3000
  retries: 1
  cleanupIntervalMs: 1000
epoch:
  zeroTimeMs: ${epochZero}
  lengthMs: ${epochLength}
`));
} else if (mode === 'membership') {
  const expected = args[0].split(',').sort();
  await wait(async () => {
    const values = await Promise.all([1, 2, 3].map(health));
    if (!values.every(v => v.membership.map(m => m.serverId).sort().join() === expected.join())) return false;
    save('active-replication-membership', values);
    save('server-ids', expected);
    return true;
  });
} else if (mode === 'subscribe') {
  save(`subscribe-node-${args[0]}`, await json(`http://127.0.0.1:800${args[0]}/v1/subscriptions/${args[1]}`, {method: 'POST'}));
} else if (mode === 'replicas') {
  const [ids, eventFile, label = 'event'] = args;
  const published = JSON.parse(fs.readFileSync(native(eventFile), 'utf8'));
  const envelope = published.envelope;
  const calculated = eventKey(envelope);
  const membership = (await health(1)).membership;
  const assigned = responsible(calculated.eventKey, membership, 2);
  const inventories = await wait(async () => {
    const values = await Promise.all([1, 2, 3].map(health));
    const holders = values.filter(v => v.eventKeys.includes(calculated.eventKey)).map(v => v.serverId).sort();
    if (holders.join() !== assigned.map(v => v.serverId).sort().join()) return false;
    return values;
  });
  save(`${label}-event-key`, {...calculated, topicId: envelope.topicId, sequenceNumber: envelope.sequenceNumber});
  save(`${label}-responsible-replicas`, assigned);
  save(`${label}-stored-inventories`, inventories);
  if (label === 'initial') {
    const nonResponsible = membership.find(v => !assigned.some(a => a.serverId === v.serverId));
    const index = ids.split(',').indexOf(nonResponsible.serverId) + 1;
    const found = await json(`http://127.0.0.1:810${index}/v1/events/${calculated.eventKey}`);
    save('non-responsible-entry-lookup', {entryServer: nonResponsible, storedEvent: found});
  }
  console.log(assigned[0].serverId);
} else if (mode === 'state') {
  save(args[1], await json(`http://127.0.0.1:800${args[0]}/v1/events/recovery-state`));
} else if (mode === 'node-ready') {
  await wait(() => json(`http://127.0.0.1:800${args[0]}/v1/events/recovery-state`));
} else if (mode === 'replication-ready') {
  await wait(() => health(Number(args[0])));
} else if (mode === 'progress') {
  const [topic, minimum] = args;
  const value = await wait(async () => {
    for (let n = 1; n <= 3; n++) {
      const progress = await json(`http://127.0.0.1:810${n}/v1/topics/${topic}/publishers`);
      if (progress.some(item => item.latestSequenceNumber >= Number(minimum))) return progress;
    }
    return false;
  });
  save('topic-publisher-log-after-offline', value);
  const latest = Math.max(...value.map(item => item.latestTimestamp));
  const filtered = await json(`http://127.0.0.1:8101/v1/topics/${topic}/publishers?sinceTimestamp=${latest}`);
  if (filtered.length !== 0) throw Error('publisher-progress timestamp filter returned stale publishers');
  save('topic-publisher-log-timestamp-filter', {sinceTimestamp: latest, publishers: filtered});
} else if (mode === 'recover') {
  const result = await json(`http://127.0.0.1:8003/v1/events/recover/${args[0]}`, {method: 'POST'});
  if (result.missingCount !== Number(args[1]) || result.deliveredCount !== Number(args[1])
      || result.unavailableEventKeys.length !== 0 || new Set(result.deliveredEventKeys).size !== Number(args[1])) {
    throw Error(`unexpected recovery result: ${JSON.stringify(result)}`);
  }
  const expected = [1, 2, 3].map(sequence => {
    const published = JSON.parse(fs.readFileSync(path.join(evidence, `published-sequence-${sequence}.json`), 'utf8'));
    return eventKey(published.envelope).eventKey;
  });
  if (result.deliveredEventKeys.join() !== expected.join()) throw Error('recovered events were not delivered in sequence order');
  save('node-3-recovery-result', result);
} else if (mode === 'health-snapshot') {
  const snapshot = await Promise.all([1, 2, 3].map(health));
  if (args[0] === 'replication-inventories-after-restart') {
    const before = JSON.parse(fs.readFileSync(path.join(evidence, 'replication-inventories-before-restart.json'), 'utf8'));
    if (snapshot.some((server, index) => server.eventKeys.join() !== before[index].eventKeys.join())) {
      throw Error('replication-server inventory changed across restart');
    }
  }
  save(args[0], snapshot);
} else {
  throw Error(`unknown mode: ${mode}`);
}
