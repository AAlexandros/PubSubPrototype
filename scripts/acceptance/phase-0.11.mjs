import fs from 'node:fs';
import path from 'node:path';
import {nativePath} from '../lib/paths.mjs';

const [mode, rawEvidence, topic, label] = process.argv.slice(2);
const evidence = nativePath(rawEvidence);
const base = 'http://127.0.0.1:8003';
const save = (name, value) => fs.writeFileSync(path.join(evidence, `${name}.json`), JSON.stringify(value, null, 2) + '\n');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(route, expected = 200) {
  const response = await fetch(base + route);
  if (response.status !== expected) throw Error(`${route}: expected ${expected}, got ${response.status}: ${await response.text()}`);
  return response.json();
}
async function until(fn, timeoutMs = 120000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try { const result = await fn(); if (result) return result; } catch (error) { last = error; }
    await sleep(500);
  }
  throw Error(`Timed out waiting for ${mode}: ${last?.message ?? 'condition not met'}`);
}
function published(sequence) {
  return JSON.parse(fs.readFileSync(path.join(evidence, `published-sequence-${sequence}.json`), 'utf8')).envelope;
}
async function listing() {
  return request(`/v1/events?topicId=${topic}&limit=10`);
}

if (mode === 'snapshot') {
  const expected = label === 'live' || label === 'first-restart' ? 1 : 4;
  const page = await until(async () => {
    const result = await listing();
    return result.events.length === expected ? result : false;
  });
  if (page.hasMore || new Set(page.events.map(e => e.eventId)).size !== expected) throw Error('duplicate or unexpected page');
  for (let sequence = 0; sequence < expected; sequence++) {
    const envelope = published(sequence);
    const listed = page.events.find(e => e.eventId === envelope.eventId);
    if (!listed || JSON.stringify(listed) !== JSON.stringify(envelope)) throw Error(`local envelope mismatch at sequence ${sequence}`);
    const byId = await request(`/v1/events/${envelope.eventId}`);
    const publisher = (await import('node:crypto')).createHash('sha256')
      .update(Buffer.from(envelope.publisherPublicKey, 'base64')).digest('hex');
    const bySequence = await request(`/v1/events?topicId=${topic}&publisherKeyId=${publisher}&sequenceNumber=${sequence}`);
    if (JSON.stringify(byId) !== JSON.stringify(envelope) || JSON.stringify(bySequence) !== JSON.stringify(envelope))
      throw Error(`single-event lookup mismatch at sequence ${sequence}`);
    save(`${label}-event-${sequence}`, byId);
  }
  const unfiltered = await request('/v1/events?limit=10');
  if (unfiltered.events.length !== expected) throw Error('unfiltered listing count mismatch');
  save(`${label}-events`, page);
  save(`${label}-all-events`, unfiltered);
  if (label === 'caught-up') {
    save('missing-local-event', await request(`/v1/events/${'ff'.repeat(32)}`, 404));
    const state = await request('/v1/events/recovery-state');
    const publisher = (await import('node:crypto')).createHash('sha256')
      .update(Buffer.from(published(0).publisherPublicKey, 'base64')).digest('hex');
    if (state[topic]?.[publisher]?.lastDeliveredSequenceNumber !== 3) throw Error('cursor is not contiguous through E3');
    save('caught-up-delivery-state', state);
  }
} else if (mode === 'catch-up') {
  const status = await until(async () => {
    const state = await request('/v1/events/catch-up/status');
    const page = await listing();
    return state.initialCompleted && page.events.length === 4 ? state : false;
  });
  if (status.topics[topic]?.state !== 'COMPLETED') throw Error(`catch-up status: ${JSON.stringify(status)}`);
  save('automatic-catch-up-status', status);
} else {
  throw Error(`unknown mode: ${mode}`);
}
