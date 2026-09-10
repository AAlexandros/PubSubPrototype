export const TELEMETRY_DATASETS = Object.freeze([
  'runs',
  'event_lifecycle',
  'message_transmissions',
  'overlay_edges',
  'protocol_cycles',
  'subscriptions',
  'persistence_operations',
  'replica_state',
  'faults',
  'resource_samples',
  'cardano_transactions'
]);

export const API_PATHS = Object.freeze({
  EVENT_PUBLISH: '/v1/events/publish',
  EVENT_RECOVER: '/v1/events/recover',
  PEER_SAMPLING_VIEW: '/v1/peer-sampling/view',
  NAVIGATION_VIEW: '/v1/navigation/view',
  DISSEMINATION_VIEW: '/v1/dissemination/view',
  SUBSCRIPTIONS: '/v1/subscriptions',
  MAINTENANCE_REPLICAS: '/v1/maintenance/replicas',
  MAINTENANCE_STATUS: '/v1/maintenance/status'
});

export const TESTBED_PATHS = Object.freeze({
  COMPOSE_FILE: '.tools/phase-0.9/compose.yaml',
  DEVNET_ENV_FILE: 'ops/infra/devnet/versions.env',
  SETTINGS_FILE: 'ops/config/phase-0.9/testbed.yaml'
});

export const nodeApiUrl = (index, apiPath) => `http://127.0.0.1:${8000 + Number(index)}${apiPath}`;
export const replicationApiUrl = (index, apiPath) => `http://127.0.0.1:${8100 + Number(index)}${apiPath}`;
