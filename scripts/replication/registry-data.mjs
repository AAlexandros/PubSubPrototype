import {createHash} from 'node:crypto';
import {mkdirSync, readFileSync, renameSync, writeFileSync} from 'node:fs';
import {dirname} from 'node:path';

const [mode, ...args] = process.argv.slice(2);
const write = (file, value) => {
  mkdirSync(dirname(file), {recursive: true});
  const temporary = `${file}.${process.pid}.tmp`;
  writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n');
  renameSync(temporary, file);
};
const bytes = hex => ({bytes: hex.toLowerCase()});
const integer = value => ({int: Number(value)});
const constructor = (index, fields = []) => ({constructor: index, fields});

if (mode === 'export') {
  const [blueprintFile, outputFile] = args;
  const blueprint = JSON.parse(readFileSync(blueprintFile, 'utf8'));
  const validator = blueprint.validators.find(v => v.title === 'replication_registry.replication_registry.spend');
  if (!validator) throw Error('replication registry validator is missing from Aiken blueprint');
  write(outputFile, {type: 'PlutusScriptV3', description: validator.title, cborHex: validator.compiledCode});
} else if (mode === 'server-id') {
  const key = JSON.parse(readFileSync(args[0], 'utf8'));
  if (!key.cborHex) throw Error('verification key has no cborHex');
  console.log(createHash('sha256').update(Buffer.from(key.cborHex, 'hex')).digest('hex'));
} else if (mode === 'datum') {
  const [file, serverId, operatorHash, host, port, start, end, active = 'true'] = args;
  write(file, constructor(0, [bytes(serverId), bytes(operatorHash), bytes(Buffer.from(host).toString('hex')),
    integer(port), integer(start), integer(end), constructor(active === 'true' ? 1 : 0)]));
} else if (mode === 'redeemer') {
  const [file, action, host, port, start, end] = args;
  write(file, action === 'unregister' ? constructor(1) : constructor(0,
    [bytes(Buffer.from(host).toString('hex')), integer(port), integer(start), integer(end)]));
} else if (mode === 'decode') {
  const [utxosFile, stateFile] = args;
  const utxos = JSON.parse(readFileSync(utxosFile, 'utf8'));
  const decoded = [];
  for (const [ref, utxo] of Object.entries(utxos)) {
    try {
      const datum = utxo.inlineDatum ?? utxo.inlineDatumJson;
      const f = datum.fields;
      if (datum.constructor !== 0 || f.length !== 7) continue;
      decoded.push({
        serverId: f[0].bytes.toLowerCase(), operator: f[1].bytes.toLowerCase(),
        host: Buffer.from(f[2].bytes, 'hex').toString(), port: Number(f[3].int),
        commitmentStartEpoch: Number(f[4].int), commitmentEndEpoch: Number(f[5].int),
        active: f[6].constructor === 1, utxo: ref
      });
    } catch {}
  }
  decoded.sort((a, b) => a.serverId.localeCompare(b.serverId));
  write(stateFile, decoded.map(({utxo, ...server}) => server));
  console.log(JSON.stringify(decoded, null, 2));
} else if (mode === 'find') {
  const [utxosFile, serverId] = args;
  const utxos = JSON.parse(readFileSync(utxosFile, 'utf8'));
  for (const [ref, utxo] of Object.entries(utxos)) {
    const d = utxo.inlineDatum ?? utxo.inlineDatumJson;
    if (d?.constructor === 0 && d.fields?.[0]?.bytes?.toLowerCase() === serverId.toLowerCase()) {
      const f = d.fields;
      console.log([ref, f[1].bytes, Buffer.from(f[2].bytes, 'hex').toString(), f[3].int, f[4].int, f[5].int,
        f[6].constructor === 1].join('\t'));
      process.exit(0);
    }
  }
  process.exit(1);
} else {
  throw Error(`unsupported registry-data mode: ${mode}`);
}
