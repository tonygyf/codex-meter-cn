import { validateSnapshot } from './protocol';

export interface Env { SNAPSHOTS: KVNamespace; READ_TOKEN: string; WRITE_TOKEN: string }
const LIMIT = 16 * 1024;
function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: {
    'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  } });
}
async function equal(a: string, b: string): Promise<boolean> {
  const digest = (s: string) => crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  const [x, y] = await Promise.all([digest(a), digest(b)]);
  const left = new Uint8Array(x), right = new Uint8Array(y);
  let difference = 0;
  for (let i = 0; i < left.length; i++) difference |= left[i] ^ right[i];
  return difference === 0;
}
async function readBody(request: Request): Promise<unknown> {
  if (Number(request.headers.get('content-length')) > LIMIT) throw new Error('too_large');
  const reader = request.body?.getReader();
  if (!reader) throw new Error('invalid_json');
  let size = 0;
  const chunks: Uint8Array[] = [];
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > LIMIT) { await reader.cancel(); throw new Error('too_large'); }
    chunks.push(value);
  }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(body));
}
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') return json({ ok: true, schemaVersion: 1 });
    if (url.pathname !== '/v1/snapshot' || url.search) return json({ error: 'not_found' }, 404);
    if (!['GET', 'PUT'].includes(request.method)) return json({ error: 'method_not_allowed' }, 405);
    if (!env.READ_TOKEN || !env.WRITE_TOKEN || env.READ_TOKEN.length < 32 ||
        env.WRITE_TOKEN.length < 32 || env.READ_TOKEN === env.WRITE_TOKEN) {
      return json({ error: 'configure_independent_tokens' }, 503);
    }
    const token = request.headers.get('authorization') ?? '';
    if (token.length > 1024 || !(await equal(token, `Bearer ${request.method === 'GET' ? env.READ_TOKEN : env.WRITE_TOKEN}`))) {
      return json({ error: 'unauthorized' }, 401);
    }
    try {
      if (request.method === 'GET') {
        const value = await env.SNAPSHOTS.get('latest');
        if (!value) return json({ error: 'no_snapshot' }, 404);
        // Keep the original expiry; offline/stale data must never look newly collected.
        return json(validateSnapshot(JSON.parse(value)));
      }
      if (!request.headers.get('content-type')?.toLowerCase().startsWith('application/json')) {
        return json({ error: 'json_required' }, 415);
      }
      let value;
      try { value = validateSnapshot(await readBody(request)); }
      catch (error) { return json({ error: error instanceof Error && error.message === 'too_large' ? 'too_large' : 'invalid_snapshot' }, error instanceof Error && error.message === 'too_large' ? 413 : 400); }
      if (value.expiresAt <= Math.floor(Date.now() / 1000)) return json({ error: 'expired_upload' }, 400);
      await env.SNAPSHOTS.put('latest', JSON.stringify(value));
      return json({ ok: true });
    } catch { return json({ error: 'storage_unavailable' }, 503); }
  },
};
