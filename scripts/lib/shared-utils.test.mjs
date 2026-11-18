import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {nativePath} from './paths.mjs';
import {loadSectionedYaml} from './simple-yaml.mjs';

test('nativePath handles slash-prefixed drive paths on Windows', () => {
  const input = '/c/work/project';
  assert.equal(nativePath(input), process.platform === 'win32' ? 'C:/work/project' : input);
});

test('testbed YAML reader retains section behavior', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pubsub-yaml-'));
  try {
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
