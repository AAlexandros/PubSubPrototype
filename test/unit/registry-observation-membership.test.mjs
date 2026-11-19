import assert from 'node:assert/strict';
import test from 'node:test';

import {matchesMemberObservation} from '../helpers/registry-observation-membership.mjs';

const serverId = '15adbf8eabc41111a5b7354ae9e84764a91af0d883e79547f82ba61a8bc3ded7';

test('member assertions accept Pub/Sub and replication server registry responses', () => {
  const pubsub = {replication: 'FRESH', members: [{endpoint: {serverId}, active: true}]};
  const replication = {replication: 'FRESH', members: [{serverId}]};
  for (const observation of [pubsub, replication]) {
    assert.equal(matchesMemberObservation(observation, serverId, 'present'), true);
    assert.equal(matchesMemberObservation(observation, serverId, 'absent'), false);
  }
});

test('member absence requires a fresh, loaded registry observation', () => {
  assert.equal(matchesMemberObservation({replication: 'FRESH', members: []}, serverId, 'absent'), true);
  assert.equal(matchesMemberObservation({replication: 'STALE', members: []}, serverId, 'absent'), false);
  assert.equal(matchesMemberObservation({replication: 'NOT_LOADED'}, serverId, 'absent'), false);
  assert.equal(matchesMemberObservation({replication: 'FRESH', members: [{serverId, active: false}]},
    serverId, 'absent'), true);
});
