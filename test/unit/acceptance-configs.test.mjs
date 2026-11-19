import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

import {loadSectionedYaml} from '../../scripts/lib/simple-yaml.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const legacyPhases = ['0.1', '0.4', '0.5', '0.6', '0.8'];

test('legacy acceptance nodes use current topic and replication registry settings', () => {
  for (const phase of legacyPhases) {
    for (let node = 1; node <= 3; node++) {
      const configPath = path.join(root, 'ops', 'config', `phase-${phase}`, `node-${node}.yaml`);
      const config = loadSectionedYaml(configPath);

      assert.equal(config.registry.cliBackend, 'HOST', configPath);
      assert.equal(config.registry.replicationRuntimeDir, '/replication-registry', configPath);
      assert.equal(config.registry.maximumAgeMs, 30000, configPath);
    }
  }
});

test('legacy acceptance Compose files mount the Cardano socket and both registries', () => {
  for (const phase of [...legacyPhases]) {
    const composePath = path.join(root, 'ops', 'infra', `phase-${phase}`, 'compose.yaml');
    const compose = fs.readFileSync(composePath, 'utf8');

    assert.match(compose, /CARDANO_NODE_SOCKET_PATH: \/node-socket\/node1\/sock/, composePath);
    assert.match(compose, /runtime\/replication-registry:\/replication-registry/, composePath);
    assert.match(compose, /runtime\/testnet\/socket:\/node-socket:ro/, composePath);
  }
});
