export function matchesMemberObservation(state, serverId, expected) {
  if (state.replication !== 'FRESH' || !Array.isArray(state.members)) return false;
  const present = state.members.some(member =>
    (member.serverId ?? member.endpoint?.serverId) === serverId && member.active !== false);
  if (expected === 'present') return present;
  if (expected === 'absent') return !present;
  throw Error(`unknown member expectation: ${expected}`);
}
