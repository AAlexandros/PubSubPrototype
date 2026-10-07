import {copyFileSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync} from 'node:fs';
import {dirname} from 'node:path';

const [mode, ...args] = process.argv.slice(2);
const pause = milliseconds => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
const write = (file, value) => {
  mkdirSync(dirname(file), {recursive: true});
  const temporary = `${file}.${process.pid}.tmp`;
  writeFileSync(temporary, JSON.stringify(value, null, 2) + '\n');
  let last;
  for (let attempt = 0; attempt < 50; attempt++) {
    try { renameSync(temporary, file); return; }
    catch (error) {
      if (!['EPERM', 'EACCES', 'EBUSY'].includes(error.code)) throw error;
      last = error;
      pause(Math.min(250, 10 + attempt * 5));
    }
  }
  // Windows/Docker bind mounts can permit content replacement while denying
  // rename-over-open-file. Fall back only after bounded atomic retries; runtime
  // pollers already retain their previous snapshot when a refresh cannot parse.
  try { copyFileSync(temporary, file); }
  catch (error) { error.cause = last; throw error; }
  finally { rmSync(temporary, {force: true}); }
};
if (mode === 'export') {
  const [blueprintFile, outputFile] = args;
  const blueprint = JSON.parse(readFileSync(blueprintFile, 'utf8'));
  const validator = blueprint.validators.find(v => v.title === 'replication_registry.replication_registry.spend');
  if (!validator) throw Error('replication registry validator is missing from Aiken blueprint');
  write(outputFile, {type: 'PlutusScriptV3', description: validator.title, cborHex: validator.compiledCode});
} else {
  throw Error(`unsupported registry-data mode: ${mode}`);
}
