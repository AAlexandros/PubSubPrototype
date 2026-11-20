import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

import {loadSectionedYaml} from '../lib/simple-yaml.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const fixtures = ['three-node', 'secure-peer-sampling', 'topic-navigation',
  'topic-dissemination-repair', 'subscriber-local-catch-up'];

test('acceptance nodes use current topic and replication registry settings', () => {
  for (const fixture of fixtures) {
    for (let node = 1; node <= 3; node++) {
      const configPath = path.join(root, 'test', 'fixtures', fixture, `node-${node}.yaml`);
      const config = loadSectionedYaml(configPath);

      assert.equal(config.registry.cliBackend, 'HOST', configPath);
      assert.equal(config.registry.replicationRuntimeDir, '/replication-registry', configPath);
      assert.equal(config.registry.maximumAgeMs, 30000, configPath);
    }
  }
});

test('acceptance Compose files mount the Cardano socket and both registries', () => {
  for (const fixture of fixtures) {
    const composePath = path.join(root, 'test', 'fixtures', fixture, 'compose.yaml');
    const compose = fs.readFileSync(composePath, 'utf8');

    assert.match(compose, /CARDANO_NODE_SOCKET_PATH: \/node-socket\/node1\/sock/, composePath);
    assert.match(compose, /runtime\/replication-registry:\/replication-registry/, composePath);
    assert.match(compose, /runtime\/testnet\/socket:\/node-socket:ro/, composePath);
  }
});
