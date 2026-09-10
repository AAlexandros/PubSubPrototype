import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {createHash, randomUUID} from 'node:crypto';

const native = value => process.platform !== 'win32' ? value
  : /^\/mnt\/[a-zA-Z]\//.test(value) ? `${value[5].toUpperCase()}:/${value.slice(7)}`
  : /^\/[a-zA-Z]\//.test(value) ? `${value[1].toUpperCase()}:/${value.slice(3)}` : value;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function scalar(value) {
  const text = value.trim();
  if (text === 'true' || text === 'false') return text === 'true';
  if (/^-?\d+(\.\d+)?$/.test(text)) return Number(text);
  return text.replace(/^['"]|['"]$/g, '');
}
function yaml(file) {
  const result = {};
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!raw.trim() || raw.trimStart().startsWith('#') || /^\s/.test(raw)) continue;
    const split = raw.indexOf(':');
    if (split > 0) result[raw.slice(0, split).trim()] = scalar(raw.slice(split + 1));
  }
  return result;
}
const [mode, rawScenario, rawRoot] = process.argv.slice(2);
if (!rawScenario) throw Error('scenario path is required');
const scenarioFile = path.resolve(native(rawScenario));
const scenario = yaml(scenarioFile);
const required = ['scenarioId','repetition','randomSeed','nodeCount','topicCount','subscriptionDistribution','publisherCount','eventCount','eventRate','payloadSize','warmupDuration','measurementDuration','telemetryLevel','replicationServerCount','replicationFactor','faultSchedule'];
for (const field of required) if (scenario[field] === undefined || scenario[field] === '') throw Error(`Scenario is missing ${field}`);
if (mode === 'inspect') { console.log(scenario.nodeCount); process.exit(0); }
if (mode !== 'run') throw Error('mode must be inspect or run');

const root = fs.realpathSync(native(rawRoot || process.cwd()));
const startedAt = new Date();
const runId = `${scenario.scenarioId}-${startedAt.toISOString().replace(/[-:.]/g, '').replace('Z','Z')}-${randomUUID().slice(0,8)}`;
const runDir = path.join(root, 'results', String(scenario.scenarioId), runId);
const rawDir = path.join(runDir, 'raw');
const logDir = path.join(runDir, 'logs');
fs.mkdirSync(rawDir, {recursive: true});
fs.mkdirSync(logDir, {recursive: true});
fs.copyFileSync(scenarioFile, path.join(runDir, 'scenario.yaml'));
const datasets = ['runs','event_lifecycle','message_transmissions','overlay_edges','protocol_cycles','subscriptions','persistence_operations','replica_state','faults','resource_samples','cardano_transactions'];
for (const dataset of datasets) fs.writeFileSync(path.join(rawDir, `${dataset}.jsonl`), '');
const monotonicOrigin = process.hrtime.bigint();
const base = () => ({runId, scenarioId: String(scenario.scenarioId), repetition: Number(scenario.repetition), randomSeed: Number(scenario.randomSeed), timestampUtc: new Date().toISOString(), monotonicTimeNs: Number(process.hrtime.bigint() - monotonicOrigin)});
const emit = (dataset, value) => fs.appendFileSync(path.join(rawDir, `${dataset}.jsonl`), JSON.stringify({...base(), ...value}) + '\n');
const nodeIds = new Map();
const progress = message => console.error(`[phase-0.9] ${new Date().toISOString()} ${message}`);
function command(program, args, options = {}) {
  const result = spawnSync(program, args, {cwd: root, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, ...options});
  if (result.error) throw Error(`${program} ${args.join(' ')} failed: ${result.error.message}`);
  if (result.status !== 0) throw Error(`${program} ${args.join(' ')} exited ${result.status}: ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}
const compose = (...args) => command('docker', ['compose','--env-file','ops/infra/devnet/versions.env','-f','.tools/phase-0.9/compose.yaml',...args]);
async function request(url, options) {
  const response = await fetch(url, options);
  const text = await response.text();
  if (!response.ok) throw Error(`${url} returned ${response.status}: ${text}`);
  return text ? JSON.parse(text) : {};
}
async function waitRequest(url, timeout = 120000) {
  const until = Date.now() + timeout;
  let last;
  while (Date.now() < until) {
    try { return await request(url); } catch (error) { last = error; await sleep(500); }
  }
  throw last || Error(`Timed out waiting for ${url}`);
}
function keyValues(line) {
  const values = {};
  for (const match of line.matchAll(/([A-Za-z][A-Za-z0-9]*)=([^\s]+)/g)) values[match[1]] = match[2];
  return values;
}
function bytes(value) {
  if (!value || value === '0B') return 0;
  const match = String(value).replaceAll(',','').match(/([\d.]+)\s*([kKmMgGtT]?i?[bB])/);
  if (!match) return null;
  const powers = {b:0,kb:1,kib:1,mb:2,mib:2,gb:3,gib:3,tb:4,tib:4};
  return Math.round(Number(match[1]) * 1024 ** powers[match[2].toLowerCase()]);
}
function collectLogs() {
  const logs = compose('logs','--no-color','--timestamps','--since',startedAt.toISOString());
  fs.writeFileSync(path.join(logDir, 'testbed.log'), logs + '\n');
  const stageNames = new Map([
    ['EVENT_PUBLISHED','PUBLISHED'], ['EVENT_RECEIVED','RECEIVED'], ['EVENT_ACCEPTED','ACCEPTED'],
    ['EVENT_FORWARDED','FORWARDED'], ['EVENT_DUPLICATE','DUPLICATE'], ['EVENT_REJECTED','REJECTED'],
    ['EVENT_PERSIST_REQUESTED','PERSIST_SUBMITTED'], ['EVENT_PERSISTED','PERSISTED'],
    ['RECOVERY_EVENT_FOUND','RECOVERY_FETCHED'], ['RECOVERY_EVENT_DELIVERED','RECOVERY_DELIVERED']
  ]);
  let sequence = 0;
  for (const line of logs.split(/\r?\n/)) {
    const marker = [...stageNames.keys()].find(name => line.includes(name));
    const values = keyValues(line);
    const serviceName = line.match(/^([^\s|]+)\s+\|/)?.[1];
    const serviceIndex = serviceName?.match(/pubsub-node-(\d+)/)?.[1];
    const service = serviceIndex ? `pubsub-node-${serviceIndex}` : serviceName;
    const observedNodeId = values.nodeId || nodeIds.get(service) || null;
    if (marker && values.topicId && values.eventId) emit('event_lifecycle', {
      nodeId: observedNodeId, topicId: values.topicId, eventId: values.eventId,
      eventKey: values.eventKey || null, publisherKeyId: values.publisherKeyId || null,
      sequenceNumber: values.sequenceNumber === undefined ? null : Number(values.sequenceNumber),
      peerNodeId: values.peerNodeId || null, stage: stageNames.get(marker), reason: values.reason || null
    });
    if (line.includes('EVENT_DISSEMINATED') && values.nodeId && values.peerNodeId) emit('message_transmissions', {
      fromNodeId: values.nodeId, toNodeId: values.peerNodeId, protocolLayer: 'DISSEMINATION', messageType: 'EVENT',
      topicId: values.topicId || null, eventId: values.eventId || null, bytes: Number(scenario.payloadSize), success: true
    });
    const operation = line.includes('REPLICA_REPAIR_COMPLETED') ? 'REPAIR' : line.includes('REPLICA_RELEASED') ? 'RELEASE' : line.includes('TOPIC_LOG_UPDATED') ? 'TOPIC_LOG_UPDATE' : line.includes('EVENT_LOOKUP_') ? 'LOOKUP' : null;
    if (operation) emit('persistence_operations', {nodeId: null, serverId: values.serverId || null, operation, topicId: values.topicId || null, eventId: values.eventId || null, eventKey: values.eventKey || null, durationMs: null, success: !line.includes('FAILED'), recordCount: 1});
    const protocol = line.includes('SECURECYCLON_CYCLE') ? 'SECURECYCLON' : line.includes('NAVIGATION_CYCLE') ? 'NAVIGATION' : line.includes('DISSEMINATION_CYCLE') ? 'DISSEMINATION' : null;
    if (protocol) emit('protocol_cycles', {componentId: values.nodeId || 'unknown', protocol, cycleNumber: ++sequence, durationMs: null, viewSize: values.viewSize === undefined ? null : Number(values.viewSize), success: !line.includes('runtime_error')});
  }
}
async function sample() {
  for (let index = 1; index <= Number(scenario.nodeCount); index++) {
    const component = `pubsub-node-${index}`;
    for (const [layer, endpoint] of [['SECURECYCLON','peer-sampling/view'],['NAVIGATION','navigation/view'],['DISSEMINATION','dissemination/view']]) {
      const before = performance.now();
      try {
        const value = await request(`http://127.0.0.1:${8000 + index}/v1/${endpoint}`);
        if (value.nodeId) nodeIds.set(component, value.nodeId);
        const found = new Map();
        function walk(item, topicId = null, role = null) {
          if (!item || typeof item !== 'object') return;
          if (typeof item.nodeId === 'string' && item.nodeId !== value.nodeId) found.set(`${item.nodeId}|${topicId}|${role}`, {peerNodeId:item.nodeId, topicId, role});
          if (Array.isArray(item)) item.forEach(child => walk(child, topicId, role));
          else for (const [key, child] of Object.entries(item)) walk(child, /^[0-9a-f]{64}$/.test(key) ? key : topicId, ['predecessor','successor','randomPeers'].includes(key) ? key.toUpperCase() : role);
        }
        walk(value);
        for (const edge of found.values()) emit('overlay_edges', {layer, nodeId:value.nodeId || component, peerNodeId:edge.peerNodeId, topicId:edge.topicId, role:edge.role, freshness:null});
        emit('protocol_cycles', {componentId:value.nodeId || component, protocol:layer, cycleNumber:Math.floor(Number(base().monotonicTimeNs) / 1e9), durationMs:performance.now()-before, viewSize:found.size, success:true});
      } catch {
        emit('protocol_cycles', {componentId:component, protocol:layer, cycleNumber:Math.floor(Number(base().monotonicTimeNs) / 1e9), durationMs:performance.now()-before, viewSize:null, success:false});
      }
    }
  }
  for (let index = 1; index <= Number(scenario.replicationServerCount); index++) {
    try {
      const [inventory, status] = await Promise.all([
        request(`http://127.0.0.1:${8100 + index}/v1/maintenance/replicas`),
        request(`http://127.0.0.1:${8100 + index}/v1/maintenance/status`)
      ]);
      for (const event of inventory.events || []) emit('replica_state', {serverId:inventory.serverId, recordType:'EVENT', eventKey:event.eventKey, topicId:event.topicId || null, present:true, responsible:null, membershipVersion:status.membershipVersion || null});
      for (const log of inventory.topicLogs || []) emit('replica_state', {serverId:inventory.serverId, recordType:'TOPIC_LOG', eventKey:null, topicId:log.topicId, present:true, responsible:null, membershipVersion:status.membershipVersion || null});
      emit('protocol_cycles', {componentId:inventory.serverId, protocol:'REPLICA_MAINTENANCE', cycleNumber:Math.floor(Number(base().monotonicTimeNs) / 1e9), durationMs:null, viewSize:(inventory.events || []).length + (inventory.topicLogs || []).length, success:true});
    } catch { /* a faulted server is represented by its fault and failed samples */ }
  }
  try {
    const stats = compose('ps','-q').split(/\r?\n/).filter(Boolean);
    if (stats.length) {
      const output = command('docker',['stats','--no-stream','--format','{{json .}}',...stats]);
      for (const line of output.split(/\r?\n/).filter(Boolean)) {
        const value = JSON.parse(line);
        const [networkRx, networkTx] = String(value.NetIO || '').split('/').map(bytes);
        const [diskRead, diskWrite] = String(value.BlockIO || '').split('/').map(bytes);
        emit('resource_samples', {componentId:value.Name, cpuPercent:Number.parseFloat(value.CPUPerc) || null, rssBytes:bytes(String(value.MemUsage || '').split('/')[0]), heapUsedBytes:null, networkRxBytes:networkRx, networkTxBytes:networkTx, diskReadBytes:diskRead, diskWriteBytes:diskWrite, storedEventCount:null, storedEventBytes:null, openConnections:null});
      }
    }
  } catch { /* Docker versions differ in stats fields; API telemetry still remains authoritative. */ }
}

let status = 'RUNNING';
let topics = [];
try {
  progress(`Creating ${scenario.topicCount} topic(s)...`);
  for (let index = 0; index < Number(scenario.topicCount); index++) {
    const before = performance.now();
    const output = command('bash',['scripts/registry/create-topic.sh','node-1',`${scenario.scenarioId}-${runId.slice(-8)}-${index}`,'-','-',String(scenario.replicationFactor),'3600']);
    const topicId = output.split(/\r?\n/).reverse().find(line => /^[0-9a-f]{64}$/.test(line.trim()))?.trim();
    if (!topicId) throw Error(`create-topic did not return a topic id: ${output}`);
    topics.push(topicId);
    emit('cardano_transactions', {operation:'CREATE_TOPIC', registry:'TOPIC', transactionId:null, topicId, serverId:null, durationMs:performance.now()-before, success:true, observationDelayMs:null});
  }
  progress(`Subscribing ${scenario.nodeCount} node(s)...`);
  await sleep(2000);
  for (let node = 1; node <= Number(scenario.nodeCount); node++) {
    const selected = scenario.subscriptionDistribution === 'round-robin' ? [topics[(node - 1) % topics.length]] : topics;
    const view = await waitRequest(`http://127.0.0.1:${8000 + node}/v1/peer-sampling/view`);
    for (const topicId of selected) {
      await request(`http://127.0.0.1:${8000 + node}/v1/subscriptions/${topicId}`, {method:'POST'});
      emit('subscriptions', {nodeId:view.nodeId || `pubsub-node-${node}`, topicId, action:'SUBSCRIBE', subscribed:true});
    }
  }
  progress(`Warm-up (${scenario.warmupDuration}s)...`);
  await sleep(Number(scenario.warmupDuration) * 1000);
  await sample();
  const faults = String(scenario.faultSchedule).split(',').map(x => x.trim()).filter(x => x && x !== 'none');
  const faultTasks = faults.map(async item => {
    const [actionTarget, rawAt] = item.split('@');
    const at = Number(rawAt);
    await sleep(at * 1000);
    try {
        progress(`Executing fault action ${item}...`);
        if (actionTarget.startsWith('replication-factor:')) {
          const factor = actionTarget.split(':')[1];
          for (const topicId of topics) command('bash',['scripts/registry/set-replication-factor.sh','node-1',topicId,factor]);
          emit('faults', {targetId:topics.join(','), faultType:'REPLICATION_FACTOR_CHANGE', action:'INJECTED', details:JSON.stringify({factor:Number(factor)})});
        } else {
          const [action, target] = actionTarget.split(':');
          if (action === 'stop' || action === 'start') compose(action,target);
          if (action === 'recover') {
            const index = Number(target.match(/(\d+)$/)?.[1]);
            await waitRequest(`http://127.0.0.1:${8000 + index}/v1/peer-sampling/view`);
            for (const topicId of topics) {
              let recovered = false;
              let lastResult;
              for (let attempt = 0; attempt < 30 && !recovered; attempt++) {
                try {
                  lastResult = await request(`http://127.0.0.1:${8000 + index}/v1/events/recover/${topicId}`, {method:'POST'});
                  recovered = Number(lastResult.deliveredCount) > 0;
                  if (!recovered) await sleep(500);
                }
                catch { await sleep(500); }
              }
              if (!recovered) throw Error(`recovery did not deliver a missed event for ${target} and ${topicId}: ${JSON.stringify(lastResult)}`);
            }
          }
          emit('faults', {targetId:target, faultType:action === 'recover' ? 'RESTART' : action.toUpperCase(), action:action === 'start' ? 'CLEARED' : 'INJECTED', details:item});
        }
        progress(`Fault action completed: ${item}`);
        return null;
      } catch (error) {
        emit('faults', {targetId:actionTarget, faultType:'INJECTION_ERROR', action:'INJECTED', details:error.message});
        return error;
      }
  });
  const intervalMs = 1000 / Number(scenario.eventRate);
  let nextSample = Date.now();
  progress(`Publishing ${scenario.eventCount} event(s) at ${scenario.eventRate}/s; faults execute on schedule...`);
  for (let index = 0; index < Number(scenario.eventCount); index++) {
    const topicId = topics[index % topics.length];
    const payload = Buffer.alloc(Number(scenario.payloadSize), 65 + index % 26).toString('base64');
    const before = performance.now();
    const value = await request(`http://127.0.0.1:8001/v1/events/publish`, {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({topicId,payload})});
    const duration = performance.now() - before;
    emit('event_lifecycle', {nodeId:null, topicId, eventId:value.eventId, eventKey:null, publisherKeyId:null, sequenceNumber:Number(value.sequenceNumber), peerNodeId:'local', stage:'PUBLISHED', reason:null});
    emit('persistence_operations', {nodeId:null, serverId:null, operation:'STORE', topicId, eventId:value.eventId, eventKey:null, durationMs:duration, success:true, recordCount:1});
    if (Date.now() >= nextSample) { await sample(); nextSample = Date.now() + 1000; }
    await sleep(intervalMs);
  }
  const elapsed = Number(scenario.eventCount) / Number(scenario.eventRate);
  progress(`Completing measurement window (${scenario.measurementDuration}s total)...`);
  await sleep(Math.max(0, Number(scenario.measurementDuration) - elapsed) * 1000);
  const faultErrors = (await Promise.all(faultTasks)).filter(Boolean);
  if (faultErrors.length) throw faultErrors[0];
  await sample();
  progress('Collecting and correlating container logs...');
  collectLogs();
  status = 'PASSED';
  progress('Raw telemetry collection passed.');
} catch (error) {
  status = 'FAILED';
  fs.writeFileSync(path.join(runDir, 'failure.txt'), error.stack + '\n');
  try { collectLogs(); } catch {}
  throw error;
} finally {
  const git = spawnSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).stdout?.trim() || 'unknown';
  const java = spawnSync(process.platform === 'win32' ? 'java.exe' : 'java',['-version'],{encoding:'utf8'}).stderr?.split(/\r?\n/)[0] || 'unknown';
  const run = {runId,scenarioId:String(scenario.scenarioId),repetition:Number(scenario.repetition),randomSeed:Number(scenario.randomSeed),gitCommit:git,javaVersion:java,startTime:startedAt.toISOString(),endTime:new Date().toISOString(),protocolConfiguration:JSON.stringify(Object.fromEntries(Object.entries(scenario).filter(([key]) => /secureCyclon|navigation|dissemination|replication/i.test(key)))),workloadConfiguration:JSON.stringify(Object.fromEntries(Object.entries(scenario).filter(([key]) => /Count|Rate|Size|Duration|Distribution|publisher/i.test(key)))),telemetryConfiguration:JSON.stringify({level:scenario.telemetryLevel,samplingIntervalMs:1000}),status};
  fs.writeFileSync(path.join(rawDir,'runs.jsonl'),JSON.stringify(run)+'\n');
  fs.writeFileSync(path.join(runDir,'metadata.json'),JSON.stringify({...run,topics,nodeCount:scenario.nodeCount,replicationServerCount:scenario.replicationServerCount},null,2)+'\n');
}
console.log(runDir);
