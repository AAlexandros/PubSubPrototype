import assert from 'node:assert/strict';
import test from 'node:test';

import {fetchJson} from '../helpers/http-json.mjs';

test('a stalled response times out and releases the pending request', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (_url, {signal}) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), {once: true});
  });
  try {
    await assert.rejects(fetchJson('http://127.0.0.1:8101/v1/ops/maintenance/status', undefined, 20),
      /request timed out after 20ms/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
