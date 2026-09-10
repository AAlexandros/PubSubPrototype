import fs from 'node:fs';

function scalar(value) {
  const text = value.trim();
  if (text === 'true' || text === 'false') return text === 'true';
  if (/^-?\d+(\.\d+)?$/.test(text)) return Number(text);
  return text.replace(/^['"]|['"]$/g, '');
}

/** Read the top-level scalar subset used by experiment scenario files. */
export function loadFlatYaml(file) {
  const result = {};
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!raw.trim() || raw.trimStart().startsWith('#') || /^\s/.test(raw)) continue;
    const split = raw.indexOf(':');
    if (split > 0) result[raw.slice(0, split).trim()] = scalar(raw.slice(split + 1));
  }
  return result;
}

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
