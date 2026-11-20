import fs from 'node:fs';

/** Read the one-level, integer-valued subset used by testbed.yaml. */
export function loadSectionedYaml(file) {
  const result = {};
  let section = null;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!raw.trim() || raw.trimStart().startsWith('#')) continue;
    const split = raw.indexOf(':');
    const key = raw.slice(0, split).trim();
    const value = raw.slice(split + 1).trim();
    if (!/^\s/.test(raw) && !value) {
      section = key;
      result[section] = {};
      continue;
    }
    const parsed = /^-?\d+$/.test(value) ? Number(value) : value;
    if (/^\s/.test(raw) && section) result[section][key] = parsed;
    else {
      section = null;
      result[key] = parsed;
    }
  }
  return result;
}
