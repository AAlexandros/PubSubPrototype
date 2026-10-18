import fs from 'node:fs';
import path from 'node:path';
import {createHash, createPrivateKey, randomBytes, sign} from 'node:crypto';
import {fileURLToPath} from 'node:url';

export const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** Adds the same Ed25519 request headers verified by SignedHttpRequest in Java. */
export function signedOptions(root, url, options = {}, audience = 'node-control', signer = 'node-1') {
  const method = (options.method || 'GET').toUpperCase();
  const body = options.body === undefined ? Buffer.alloc(0) : Buffer.from(options.body);
  const keyDir = process.env.PUBSUB_OPERATOR_KEY_DIR || path.join(root, 'ops', 'infra', 'devnet', 'keys', signer);
  const secret = Buffer.from(JSON.parse(fs.readFileSync(path.join(keyDir, 'payment.skey'), 'utf8')).cborHex, 'hex');
  const publicKey = Buffer.from(JSON.parse(fs.readFileSync(path.join(keyDir, 'payment.vkey'), 'utf8')).cborHex, 'hex');
  if (secret.length !== 34 || secret[0] !== 0x58 || secret[1] !== 0x20 ||
      publicKey.length !== 34 || publicKey[0] !== 0x58 || publicKey[1] !== 0x20)
    throw Error('Expected 32-byte Cardano Ed25519 keys');
  const privateKey = createPrivateKey({key: Buffer.concat([
    Buffer.from('302e020100300506032b657004220420', 'hex'), secret.subarray(2)
  ]), format: 'der', type: 'pkcs8'});
  const signerId = createHash('sha256').update(publicKey).digest('hex');
  const timestamp = Date.now();
  const nonce = randomBytes(16).toString('base64url');
  const parsed = new URL(url);
  const requestPath = parsed.pathname + parsed.search;
  const bodyHash = createHash('sha256').update(body).digest('hex');
  const message = `pubsub-http-v1\n${method}\n${requestPath}\n${bodyHash}\n${audience}\n${timestamp}\n${nonce}`;
  const signature = sign(null, Buffer.from(message, 'utf8'), privateKey).toString('base64');
  return {...options, headers: {...options.headers,
    'X-PubSub-Signer-Id': signerId,
    'X-PubSub-Public-Key': publicKey.toString('hex'),
    'X-PubSub-Timestamp': String(timestamp),
    'X-PubSub-Nonce': nonce,
    'X-PubSub-Signature': signature
  }};
}
