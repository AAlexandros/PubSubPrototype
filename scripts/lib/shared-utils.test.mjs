import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {API_PATHS, TELEMETRY_DATASETS, nodeApiUrl, replicationApiUrl} from './contracts.mjs';
import {nativePath} from './paths.mjs';
import {loadFlatYaml, loadSectionedYaml} from './simple-yaml.mjs';

test('shared API builders preserve the testbed port convention', () => {
  assert.equal(nodeApiUrl(1, API_PATHS.EVENT_PUBLISH), 'http://127.0.0.1:8001/v1/events/publish');
  assert.equal(replicationApiUrl(3, API_PATHS.MAINTENANCE_STATUS),
    'http://127.0.0.1:8103/v1/maintenance/status');
  assert.equal(new Set(TELEMETRY_DATASETS).size, TELEMETRY_DATASETS.length);
});

test('nativePath handles slash-prefixed drive paths on Windows', () => {
  const input = '/c/work/project';
  assert.equal(nativePath(input), process.platform === 'win32' ? 'C:/work/project' : input);
});

test('YAML readers retain their supported scalar and section behavior', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pubsub-yaml-'));
  try {
    const flat = path.join(directory, 'scenario.yaml');
    fs.writeFileSync(flat, "enabled: true\nrate: 1.5\nname: 'sample'\n  ignored: nested\n");
    assert.deepEqual(loadFlatYaml(flat), {enabled: true, rate: 1.5, name: 'sample'});

    const sectioned = path.join(directory, 'testbed.yaml');
    fs.writeFileSync(sectioned, 'port: 8000\nnavigation:\n  capacity: 20\n  mode: ring\n');
    assert.deepEqual(loadSectionedYaml(sectioned), {
      port: 8000,
      navigation: {capacity: 20, mode: 'ring'}
    });
  } finally {
    fs.rmSync(directory, {recursive: true, force: true});
  }
});
