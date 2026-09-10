import fs from 'node:fs';
import path from 'node:path';

const native = value => process.platform !== 'win32' ? value
  : /^\/mnt\/[a-zA-Z]\//.test(value) ? `${value[5].toUpperCase()}:/${value.slice(7)}`
  : /^\/[a-zA-Z]\//.test(value) ? `${value[1].toUpperCase()}:/${value.slice(3)}` : value;
const [rawRun, rawEvidence, rawRoot] = process.argv.slice(2);
const run = path.resolve(native(rawRun));
const evidence = path.resolve(native(rawEvidence));
const root = path.resolve(native(rawRoot));
fs.mkdirSync(evidence, {recursive:true});
const datasets = ['runs','event_lifecycle','message_transmissions','overlay_edges','protocol_cycles','subscriptions','persistence_operations','replica_state','faults','resource_samples','cardano_transactions'];
const counts = {};
for (const name of datasets) {
  const jsonl = path.join(run,'raw',`${name}.jsonl`);
  const parquet = path.join(run,'parquet',`${name}.parquet`);
  if (!fs.existsSync(jsonl)) throw Error(`missing raw dataset ${name}`);
  if (!fs.existsSync(parquet)) throw Error(`missing Parquet dataset ${name}`);
  const magic = fs.readFileSync(parquet).subarray(0,4).toString('ascii');
  if (magic !== 'PAR1') throw Error(`${name}.parquet has invalid magic ${magic}`);
  counts[name] = fs.readFileSync(jsonl,'utf8').split(/\r?\n/).filter(Boolean).length;
}
for (const required of ['runs','event_lifecycle','overlay_edges','protocol_cycles','subscriptions','persistence_operations','replica_state','faults','resource_samples','cardano_transactions']) {
  if (counts[required] === 0) throw Error(`${required} has no observations`);
}
const lifecycle = fs.readFileSync(path.join(run,'raw','event_lifecycle.jsonl'),'utf8');
for (const stage of ['PUBLISHED','ACCEPTED','PERSISTED','RECOVERY_FETCHED','RECOVERY_DELIVERED']) {
  if (!lifecycle.includes(`\"stage\":\"${stage}\"`)) throw Error(`event lifecycle is missing ${stage}`);
}
const logs = fs.readFileSync(path.join(run,'logs','testbed.log'),'utf8');
for (const marker of ['PEER_SAMPLING_STARTED','NAVIGATION_CYCLE','DISSEMINATION_CYCLE','EVENT_PERSISTED','RECOVERY_EVENT_DELIVERED','REPLICA_REPAIR_COMPLETED']) {
  if (!logs.includes(marker)) throw Error(`integrated logs are missing ${marker}`);
}
const rawBefore = datasets.map(name => [name,fs.statSync(path.join(run,'raw',`${name}.jsonl`)).size]);
if (!fs.existsSync(path.join(run,'derived','summary.json'))) throw Error('derived summary missing');
if (!fs.existsSync(path.join(root,'results','data-dictionary.md'))) throw Error('data dictionary missing');
const docs = fs.readdirSync(path.join(root,'docs','architecture')).filter(name => name.endsWith('.md')).sort();
if (docs.length < 8) throw Error(`expected at least 8 architecture Markdown files, found ${docs.length}`);
for (const name of docs.filter(name => name !== 'README.md')) {
  if (!fs.readFileSync(path.join(root,'docs','architecture',name),'utf8').includes('```mermaid')) throw Error(`${name} has no Mermaid source block`);
}
fs.writeFileSync(path.join(evidence,'telemetry-file-inventory.json'),JSON.stringify({run,datasets:counts,rawPreserved:rawBefore},null,2)+'\n');
fs.writeFileSync(path.join(evidence,'architecture-inventory.json'),JSON.stringify({files:docs,mermaidBlocks:docs.reduce((count,name) => count + (fs.readFileSync(path.join(root,'docs','architecture',name),'utf8').match(/```mermaid/g)||[]).length,0)},null,2)+'\n');
fs.copyFileSync(path.join(run,'metadata.json'),path.join(evidence,'scenario-metadata.json'));
fs.copyFileSync(path.join(run,'derived','summary.json'),path.join(evidence,'derived-summary.json'));
fs.writeFileSync(path.join(evidence,'acceptance-audit.json'),JSON.stringify({passed:true,run,datasetCounts:counts,requiredMarkers:true,rawPreserved:true,parquetMagic:'PAR1',architectureFiles:docs.length},null,2)+'\n');
console.log('Phase 0.9 evidence audit passed');
