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
async function wait(check, timeout = 180000) {
  const end = Date.now() + timeout;
  let last;
  while (Date.now() < end) {
    try { const result = await check(); if (result) return result; } catch (error) { last = error; }
    await sleep(500);
  }
  throw Error(`Timed out in ${mode}${last ? `: ${last.message}` : ''}`);
}
const health = n => json(`http://127.0.0.1:810${n}/v1/health`);
const status = n => json(`http://127.0.0.1:810${n}/v1/maintenance/status`);
const inventory = n => json(`http://127.0.0.1:810${n}/v1/maintenance/replicas`);
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
function eventKey(envelope) {
  const publisher = sha(Buffer.from(envelope.publisherPublicKey, 'base64'));
  const sequence = Buffer.alloc(8);
  sequence.writeBigUInt64BE(BigInt(envelope.sequenceNumber));
  return sha(Buffer.concat([Buffer.from(envelope.topicId, 'hex'), Buffer.from(publisher, 'hex'), sequence]));
}
function responsible(key, membership, factor) {
  const ring = 1n << 256n;
  const numeric = BigInt(`0x${key}`);
  return [...membership].sort((a, b) => {
    const aValue = BigInt(`0x${a.serverId}`), bValue = BigInt(`0x${b.serverId}`);
    const directA = aValue > numeric ? aValue - numeric : numeric - aValue;
    const directB = bValue > numeric ? bValue - numeric : numeric - bValue;
    const da = directA < ring - directA ? directA : ring - directA;
    const db = directB < ring - directB ? directB : ring - directB;
    return da < db ? -1 : da > db ? 1 : a.serverId.localeCompare(b.serverId);
  }).slice(0, Math.min(factor, membership.length));
}
const indices = value => value.split(',').filter(Boolean).map(Number);
async function availableInventories(serverIndices) {
  const values = await Promise.all(serverIndices.map(async index => {
    try { return {index, value: await inventory(index)}; } catch { return null; }
  }));
  return values.filter(Boolean);
}

if (mode === 'prepare-devnet') {
  const root = fs.realpathSync(process.cwd());
  const backup = path.resolve(root, '.tools', 'phase-0.8-devnet-backups', String(Date.now()));
  for (const name of ['runtime', 'keys', 'state']) {
    const source = path.resolve(root, 'ops', 'infra', 'devnet', name);
    if (!fs.existsSync(source)) continue;
    if (fs.lstatSync(source).isSymbolicLink()) throw Error('refusing to archive a devnet symlink');
    fs.mkdirSync(backup, {recursive: true});
    fs.renameSync(source, path.resolve(backup, name));
  }
} else if (mode === 'server-configs') {
  const [rawRuntime, ids, epochZero = '0', epochLength = '432000000'] = args;
  const runtime = native(rawRuntime);
  const signers = ['node-1', 'node-2', 'node-3', 'registry-deployer'];
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
  topicRegistrySigner: ${signers[index]}
timing:
  connectionTimeoutMs: 500
  requestTimeoutMs: 1500
  retries: 0
  cleanupIntervalMs: 1000
  failureProbeAttempts: 3
  failureProbeTimeoutMs: 500
  maintenanceIntervalMs: 500
epoch:
  zeroTimeMs: ${epochZero}
  lengthMs: ${epochLength}
`));
} else if (mode === 'membership') {
  const [expectedIds, activeIndices, label] = args;
  const expected = expectedIds.split(',').sort();
  const values = await wait(async () => {
    const observed = await Promise.all(indices(activeIndices).map(health));
    return observed.every(value => value.membership.map(item => item.serverId).sort().join() === expected.join())
      ? observed : false;
  });
  save(label, values);
} else if (mode === 'subscribe') {
  save(`subscribe-node-${args[0]}`, await json(`http://127.0.0.1:800${args[0]}/v1/subscriptions/${args[1]}`, {method: 'POST'}));
} else if (mode === 'replicas') {
  const [idsCsv, eventFile, factorValue, activeIndices, label] = args;
  const ids = idsCsv.split(',');
  const envelope = JSON.parse(fs.readFileSync(native(eventFile), 'utf8')).envelope;
  const key = eventKey(envelope);
  const reference = await health(indices(activeIndices)[0]);
  const availableIds = new Set(indices(activeIndices).map(index => ids[index - 1]));
  const effectiveMembership = reference.membership.filter(item => availableIds.has(item.serverId));
  const expected = responsible(key, effectiveMembership, Number(factorValue)).map(item => item.serverId).sort();
  const values = await wait(async () => {
    const observed = await availableInventories(indices(activeIndices));
    const holders = observed.filter(item => item.value.events.some(event => event.eventKey === key))
      .map(item => item.value.serverId).sort();
    return holders.join() === expected.join() ? observed : false;
  });
  save(`${label}-placement`, {eventKey: key, expected, inventories: values});
  console.log(ids.indexOf(expected[0]) + 1);
} else if (mode === 'failure-transition') {
  const [failedId, activeIndices] = args;
  const servers = indices(activeIndices);
  const suspected = await wait(async () => {
    const values = await Promise.all(servers.map(status));
    return values.some(value => value.suspectedPeers.includes(failedId)) ? values : false;
  }, 30000);
  if (suspected.some(value => value.confirmedUnavailablePeers.includes(failedId))) {
    throw Error('peer was confirmed down before suspicion evidence was captured');
  }
  save('failure-suspected', suspected);
  const confirmed = await wait(async () => {
    const values = await Promise.all(servers.map(status));
    return values.some(value => value.confirmedUnavailablePeers.includes(failedId)) ? values : false;
  });
  save('failure-confirmed', confirmed);
} else if (mode === 'maintenance') {
  const [activeIndices, label] = args;
  save(label, await Promise.all(indices(activeIndices).map(status)));
} else if (mode === 'lookup') {
  const [serverIndex, eventFile, label] = args;
  const published = JSON.parse(fs.readFileSync(native(eventFile), 'utf8'));
  const stored = await json(`http://127.0.0.1:810${serverIndex}/v1/events/${eventKey(published.envelope)}`);
  save(label, stored);
} else if (mode === 'state') {
  save(args[1], await json(`http://127.0.0.1:800${args[0]}/v1/events/recovery-state`));
} else if (mode === 'node-ready') {
  await wait(() => json(`http://127.0.0.1:800${args[0]}/v1/events/recovery-state`));
} else if (mode === 'recover') {
  const result = await json(`http://127.0.0.1:800${args[1]}/v1/events/recover/${args[0]}`, {method: 'POST'});
  if (result.unavailableEventKeys.length || result.deliveredCount < Number(args[2])) {
    throw Error(`unexpected recovery result: ${JSON.stringify(result)}`);
  }
  save('offline-subscriber-recovery-after-repair', result);
} else if (mode === 'recovered') {
  const [recoveredId, activeIndices] = args;
  const values = await wait(async () => {
    const observed = await Promise.all(indices(activeIndices).map(status));
    return observed.every(value => !value.confirmedUnavailablePeers.includes(recoveredId)) ? observed : false;
  });
  save('failed-server-recovery', values);
} else if (mode === 'topic-log-factor') {
  const [topicId, factorValue, activeIndices, label] = args;
  const values = await wait(async () => {
    const observed = await availableInventories(indices(activeIndices));
    const holders = observed.filter(item => item.value.topicLogs.some(log => log.topicId === topicId));
    return holders.length === Number(factorValue) ? observed : false;
  });
  save(label, values);
} else {
  throw Error(`unknown mode: ${mode}`);
}
