export async function fetchJson(url, options, timeoutMs = 5000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {...options, signal: controller.signal});
    const body = await response.text();
    if (!response.ok) throw Error(`${url} returned ${response.status}: ${body}`);
    return JSON.parse(body);
  } catch (error) {
    if (controller.signal.aborted) throw Error(`${url} request timed out after ${timeoutMs}ms`, {cause: error});
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}
