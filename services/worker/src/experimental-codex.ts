import { type QuotaWindow, type Snapshot, validateSnapshot } from './protocol';

// This backend is used by Codex but is not a published stable API. The owner
// explicitly opts into device authorization and the experimental integration.
const CODEX_USAGE_URL = 'https://chatgpt.com/backend-api/wham/usage';
const MAX_RESPONSE_BYTES = 128 * 1024;

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function normalizeExperimentalUsage(value: unknown, now = Math.floor(Date.now() / 1000)): Snapshot {
  if (!isObject(value) || !isObject(value.rate_limit)) throw new Error('usage_unavailable');
  const windows: Snapshot['windows'] = { fiveHour: null, weekly: null };
  for (const name of ['primary_window', 'secondary_window']) {
    const input: unknown = value.rate_limit[name];
    if (input == null) continue;
    if (!isObject(input)) throw new Error('invalid_usage_window');
    const duration: unknown = input.limit_window_seconds;
    const key = duration === 18000 ? 'fiveHour' : duration === 604800 ? 'weekly' : null;
    if (!key || input.used_percent == null) continue;
    if (windows[key] !== null) throw new Error('duplicate_usage_window');
    const window: QuotaWindow = {
      usedPercent: input.used_percent as number,
      windowMinutes: key === 'fiveHour' ? 300 : 10080,
      resetsAt: input.reset_at == null ? null : input.reset_at as number,
    };
    windows[key] = window;
  }
  return validateSnapshot({ schemaVersion: 1, collectedAt: now, expiresAt: now + 900, windows }, now);
}

export async function fetchExperimentalUsage(accessToken: string, accountId: string, fetcher = fetch): Promise<Snapshot> {
  if (!accessToken || accessToken.length > 16384 || /[\r\n]/.test(accessToken) ||
      !accountId || accountId.length > 256 || /[\r\n]/.test(accountId)) throw new Error('invalid_credentials');
  let response: Response;
  try {
    response = await fetcher(CODEX_USAGE_URL, {
      method: 'GET', redirect: 'manual', signal: AbortSignal.timeout(15000),
      headers: { Authorization: `Bearer ${accessToken}`, 'ChatGPT-Account-Id': accountId, Accept: 'application/json',
        'User-Agent': 'CodexMeterCN/0.1 (Cloudflare Worker; personal quota monitor)' },
    });
  } catch { throw new Error('upstream_unavailable'); }
  if (response.status === 401) { await response.body?.cancel(); throw new Error('reauthorization_required'); }
  if (response.status === 403) {
    const challenged = response.headers.get('cf-mitigated') === 'challenge' || response.headers.get('content-type')?.includes('text/html');
    await response.body?.cancel();
    throw new Error(challenged ? 'upstream_access_challenge' : 'upstream_forbidden');
  }
  if (response.status === 429) { await response.body?.cancel(); throw new Error('upstream_rate_limited'); }
  if (!response.ok || !response.body) throw new Error('upstream_unavailable');
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    length += value.length;
    if (length > MAX_RESPONSE_BYTES) { await reader.cancel(); throw new Error('upstream_response_too_large'); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try {
    return normalizeExperimentalUsage(JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes)));
  } catch { throw new Error('invalid_upstream_response'); }
}
