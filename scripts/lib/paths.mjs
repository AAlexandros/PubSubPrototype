/** Convert Git Bash/WSL drive paths when the current Node runtime is Windows. */
export function nativePath(value) {
  if (process.platform !== 'win32') return value;
  if (/^\/mnt\/[a-zA-Z]\//.test(value)) {
    return `${value[5].toUpperCase()}:/${value.slice(7)}`;
  }
  if (/^\/[a-zA-Z]\//.test(value)) {
    return `${value[1].toUpperCase()}:/${value.slice(3)}`;
  }
  return value;
}
