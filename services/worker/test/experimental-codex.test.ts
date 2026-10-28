import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchExperimentalUsage, normalizeExperimentalUsage } from '../src/experimental-codex';

const response = () => ({ account_id: 'not-forwarded', access_token: 'not-forwarded', rate_limit: {
  primary_window: { used_percent: 37, limit_window_seconds: 18000, reset_at: 1793114400 },
  secondary_window: { used_percent: 68, limit_window_seconds: 604800, reset_at: null },
} });

test('experimental response is reduced to the shared v1 snapshot', () => {
  const snapshot = normalizeExperimentalUsage(response(), 1793100000);
  assert.equal(snapshot.windows.fiveHour?.usedPercent, 37);
  assert.equal(snapshot.windows.weekly?.usedPercent, 68);
  assert.equal(snapshot.windows.weekly?.resetsAt, null);
  assert.equal(snapshot.expiresAt, 1793100900);
  assert.ok(!JSON.stringify(snapshot).includes('not-forwarded'));
});

test('unknown windows remain unknown, invalid and duplicate windows fail', () => {
  const unknown = response(); unknown.rate_limit.primary_window.limit_window_seconds = 60;
  assert.equal(normalizeExperimentalUsage(unknown, 1793100000).windows.fiveHour, null);
  const invalid = response(); invalid.rate_limit.primary_window.used_percent = 101;
  assert.throws(() => normalizeExperimentalUsage(invalid, 1793100000));
  const duplicate = response(); duplicate.rate_limit.secondary_window.limit_window_seconds = 18000;
  assert.throws(() => normalizeExperimentalUsage(duplicate, 1793100000));
  assert.throws(() => normalizeExperimentalUsage({rate_limit: null}, 1793100000));
});

test('upstream request is fixed to Codex endpoint with no redirects', async () => {
  let called = false;
  const fake = async (url: string | URL | Request, init?: RequestInit): Promise<Response> => {
    called = true; assert.equal(url, 'https://chatgpt.com/backend-api/wham/usage');
    assert.equal(init?.redirect, 'manual');
    assert.equal(new Headers(init?.headers).get('Authorization'), 'Bearer synthetic-token');
    assert.equal(new Headers(init?.headers).get('ChatGPT-Account-Id'), 'synthetic-account');
    return Response.json(response());
  };
  const result = await fetchExperimentalUsage('synthetic-token', 'synthetic-account', fake as typeof fetch);
  assert.equal(result.windows.fiveHour?.usedPercent, 37); assert.ok(called);
});

test('denials, redirect, oversized response and invalid credentials fail closed', async () => {
  for (const status of [301, 401, 403, 500]) {
    const fake = async () => new Response('', { status });
    await assert.rejects(fetchExperimentalUsage('synthetic-token', 'synthetic-account', fake as typeof fetch));
  }
  const oversized = async () => new Response('x'.repeat(130 * 1024));
  await assert.rejects(fetchExperimentalUsage('synthetic-token', 'synthetic-account', oversized as typeof fetch), /too_large/);
  await assert.rejects(fetchExperimentalUsage('a\nb', 'account'), /invalid_credentials/);
});
