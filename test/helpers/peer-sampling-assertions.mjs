import fs from 'node:fs';
import net from 'node:net';
import crypto from 'node:crypto';
import path from 'node:path';

// Runtime assertions use HTTP snapshots and a framed unauthenticated HELLO rejection.
const [mode, evidence] = process.argv.slice(2);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function views(nodes = [1, 2, 3]) {
  return Promise.all(nodes.map(async n => {
    const response = await fetch(`http://127.0.0.1:800${n}/v1/peer-sampling/view`);
    if (!response.ok) throw Error(`View request failed: ${response.status}`);
    const value = await response.json();
    // SecureCyclon may hold distinct descriptors for the same peer endpoint.
    if (!/^[a-f0-9]{64}$/.test(value.nodeId)
        || !Number.isInteger(value.capacity) || value.capacity !== 2 || !Array.isArray(value.view)
        || value.view.length > value.capacity
        || value.view.some(p => !p || p.nodeId === value.nodeId || !/^[a-f0-9]{64}$/.test(p.nodeId)
          || typeof p.host !== 'string' || !p.host
          || !Number.isInteger(p.port) || p.port < 1 || p.port > 65535)) {
      throw Error(`Invalid view from node-${n}: ${JSON.stringify(value)}`);
    }
    return value;
  }));
}
async function wait(check, timeout = 90000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (await check()) return;
    await sleep(200);
  }
  throw Error(`Timed out: ${mode}`);
}
function save(name, data) { fs.writeFileSync(`${evidence}/${name}.json`, JSON.stringify(data, null, 2) + '\n'); }

if (mode === 'prepare-devnet') {
  // Preserve old generated keys/state outside evidence; all rename targets stay inside this checkout.
  const root = fs.realpathSync(process.cwd());
  const backup = path.resolve(root, '.tools', 'secure-peer-sampling-devnet-backups', String(Date.now()));
  for (const name of ['runtime', 'keys', 'state']) {
    const source = path.resolve(root, 'test', 'devnet', name);
    const target = path.resolve(backup, name);
    if (!source.startsWith(root + path.sep) || !target.startsWith(root + path.sep)) throw Error('Unsafe archive path');
    if (fs.existsSync(source)) {
      if (fs.lstatSync(source).isSymbolicLink()) throw Error('Refusing to archive symlink');
      fs.mkdirSync(backup, {recursive: true}); fs.renameSync(source, target);
    }
  }
  console.log(`Previous generated devnet state archived at ${backup}`);
} else if (mode === 'initial') {
  const initial = await views(); save('initial-views', initial);
} else if (mode === 'converge') {
  await wait(async () => {
    const current = await views();
    if (!current[1].view.some(p => p.nodeId === current[2].nodeId)
        && !current[2].view.some(p => p.nodeId === current[1].nodeId)) return false;
    save('converged-views', current); return true;
  });
} else if (mode === 'removed') {
  const departed = JSON.parse(fs.readFileSync(`${evidence}/initial-views.json`))[2].nodeId;
  let since = 0;
  const snapshots = [];
  await wait(async () => {
    const current = await views([1, 2]);
    if (current.some(n => n.view.some(p => p.nodeId === departed))) { since = 0; snapshots.length = 0; return false; }
    since ||= Date.now(); snapshots.push(current);
    if (Date.now() - since < 12000) return false;
    save('failure-removal-views', snapshots); return true;
  });
} else if (mode === 'restarted') {
  const departed = JSON.parse(fs.readFileSync(`${evidence}/initial-views.json`))[2].nodeId;
  await wait(async () => {
    const current = await views();
    if (current[2].nodeId !== departed) throw Error('Persistent identity changed on restart');
    if (!current.slice(0, 2).some(n => n.view.some(p => p.nodeId === departed))) return false;
    save('restart-rediscovery-views', current); return true;
  });
} else if (mode === 'invalid') {
  const before = await views(); save('before-invalid-identity', before);
  const fake = crypto.createHash('sha256').update('secure-peer-sampling-invalid-identity').digest('hex');
  const frame = message => {
    const body = Buffer.from(JSON.stringify(message));
    const header = Buffer.alloc(4); header.writeUInt32BE(body.length); return Buffer.concat([header, body]);
  };
  await new Promise((resolve, reject) => {
    const socket = net.connect(7001, '127.0.0.1'); let bytes = Buffer.alloc(0);
    let settled = false;
    const timer = setTimeout(() => finish(Error('Unauthenticated HELLO was not rejected')), 5000);
    function finish(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      if (error) reject(error); else resolve();
    }
    socket.on('error', error => finish(error));
    socket.on('connect', () => socket.write(frame({type: 'HELLO', version: 1, nodeId: fake, nodeName: 'invalid-fixture'})));
    socket.on('data', chunk => {
      bytes = Buffer.concat([bytes, chunk]);
      if (bytes.length < 4 || bytes.length < bytes.readUInt32BE(0) + 4) return;
      const hello = JSON.parse(bytes.subarray(4, 4 + bytes.readUInt32BE(0)));
      if (hello.type === 'HELLO_ACK') finish(Error('Server acknowledged an unauthenticated HELLO'));
      else finish(Error(`Unexpected response to unauthenticated HELLO: ${hello.type}`));
    });
    socket.on('close', () => {
      if (bytes.length === 0) finish();
      else if (!settled) finish(Error('Connection closed after an unexpected response'));
    });
  });
  const after = await views();
  if (after.some(n => n.view.some(p => p.nodeId === fake))) throw Error('Invalid sender entered view');
  save('after-invalid-identity', after);
} else throw Error(`Unknown mode ${mode}`);
console.log(`Secure peer sampling assertion passed: ${mode}`);
