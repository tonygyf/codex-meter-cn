import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import worker, { type Env } from '../src/index';
import { validateSnapshot } from '../src/protocol';

const fixture = JSON.parse(readFileSync(new URL('../../../packages/protocol/fixtures/snapshot-v1.json', import.meta.url), 'utf8'));
const now = Math.floor(Date.now() / 1000);
const snapshot = () => ({ ...structuredClone(fixture), collectedAt: now, expiresAt: now + 900 });
function setup() {
  const values = new Map<string, string>();
  const env = { READ_TOKEN: 'test-read-'.padEnd(40, 'r'), WRITE_TOKEN: 'test-write-'.padEnd(40, 'w'),
    SNAPSHOTS: { get: async (key: string) => values.get(key) ?? null,
      put: async (key: string, value: string) => { values.set(key, value); } } } as unknown as Env;
  const request = (method: string, token: string, body?: unknown, extra: Record<string, string> = {}) => new Request('https://meter.example/v1/snapshot', {
    method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra },
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  });
  return { env, values, request };
}
test('shared fixture, missing windows and expiry are preserved', () => {
  assert.equal(validateSnapshot(fixture, fixture.collectedAt).windows.fiveHour?.usedPercent, 37);
  const data = snapshot(); data.windows.fiveHour = null;
  assert.equal(validateSnapshot(data).windows.fiveHour, null);
});
test('reject unknown version, secrets, invalid percentages, windows and future dates', () => {
  for (const change of [
    (v: any) => { v.schemaVersion = 2; }, (v: any) => { v.accessToken = 'private'; },
    (v: any) => { v.windows.weekly.usedPercent = -1; },
    (v: any) => { v.windows.weekly.usedPercent = 101; },
    (v: any) => { v.windows.weekly.windowMinutes = 300; },
    (v: any) => { v.windows.weekly.resetsAt = 'tomorrow'; },
    (v: any) => { v.collectedAt = now + 120; },
    (v: any) => { v.expiresAt = now + 3601; },
  ]) { const data = snapshot(); change(data); assert.throws(() => validateSnapshot(data)); }
});
test('read and write tokens cannot substitute for one another', async () => {
  const {env, request} = setup();
  assert.equal((await worker.fetch(request('PUT', env.READ_TOKEN, snapshot()), env)).status, 401);
  assert.equal((await worker.fetch(request('GET', env.WRITE_TOKEN), env)).status, 401);
  assert.equal((await worker.fetch(request('GET', ''), env)).status, 401);
  assert.equal((await worker.fetch(request('GET', env.READ_TOKEN), {...env, WRITE_TOKEN: env.READ_TOKEN})).status, 503);
});
test('write then read round trip, stale GET never extends timestamps', async () => {
  const {env, values, request} = setup();
  assert.equal((await worker.fetch(request('GET', env.READ_TOKEN), env)).status, 404);
  const data = snapshot();
  assert.equal((await worker.fetch(request('PUT', env.WRITE_TOKEN, data), env)).status, 200);
  assert.deepEqual(await (await worker.fetch(request('GET', env.READ_TOKEN), env)).json(), data);
  const stale = {...data, collectedAt: now - 1800, expiresAt: now - 900};
  values.set('latest', JSON.stringify(stale));
  assert.deepEqual(await (await worker.fetch(request('GET', env.READ_TOKEN), env)).json(), stale);
  assert.equal((await worker.fetch(request('PUT', env.WRITE_TOKEN, stale), env)).status, 400);
});
test('malformed and oversized uploads are rejected without storage', async () => {
  const {env, values, request} = setup();
  assert.equal((await worker.fetch(request('PUT', env.WRITE_TOKEN, '{'), env)).status, 400);
  assert.equal((await worker.fetch(request('PUT', env.WRITE_TOKEN, 'x'.repeat(17000)), env)).status, 413);
  assert.equal((await worker.fetch(request('PUT', env.WRITE_TOKEN, snapshot(), {'Content-Type': 'text/plain'}), env)).status, 415);
  assert.equal(values.size, 0);
});
test('storage failure and invalid persisted data do not leak internal errors', async () => {
  const {env, values, request} = setup();
  values.set('latest', '{bad');
  assert.equal((await worker.fetch(request('GET', env.READ_TOKEN), env)).status, 503);
  const broken = {...env, SNAPSHOTS: {get: async () => {throw new Error('sensitive');}}} as unknown as Env;
  const response = await worker.fetch(request('GET', env.READ_TOKEN), broken);
  assert.equal(response.status, 503); assert.ok(!(await response.text()).includes('sensitive'));
});
