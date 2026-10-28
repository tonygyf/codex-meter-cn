import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CodexOAuth, seal, unseal, type TokenRecord } from '../src/oauth';

const key = Buffer.alloc(32, 7).toString('base64');
const identity = { sub: 'synthetic-subject', 'https://api.openai.com/auth': { chatgpt_account_id: 'synthetic-account' } };
const previous: TokenRecord = { accessToken: 'old-token', refreshToken: 'old-refresh', accountId: 'synthetic-account', subject: 'synthetic-subject', expiresAt: 900 };

test('vault encrypts with a random IV and rejects tampering and wrong keys', async () => {
  const raw = { accessToken: 'never-plaintext', refreshToken: 'another-secret' };
  const first = await seal(raw, key), second = await seal(raw, key);
  assert.notEqual(first, second); assert.ok(!first.includes('never-plaintext'));
  assert.deepEqual(await unseal(first, key), raw);
  await assert.rejects(unseal(first, Buffer.alloc(32, 8).toString('base64')));
  const tampered = JSON.parse(first); tampered.ciphertext = 'AAAA' + tampered.ciphertext.slice(4);
  await assert.rejects(unseal(JSON.stringify(tampered), key));
});

test('device authorization and code exchange use fixed TLS endpoints and verified identity', async () => {
  const calls: string[] = [];
  const fake = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); calls.push(url); assert.equal(init?.redirect, 'manual');
    if (url.endsWith('/usercode')) return Response.json({device_auth_id:'private-device', user_code:'ABCD-EFGH', interval:'5'});
    if (url.endsWith('/deviceauth/token')) return Response.json({authorization_code:'private-code', code_verifier:'private-verifier'});
    assert.ok(String(init?.body).includes('grant_type=authorization_code'));
    return Response.json({access_token:'access',refresh_token:'refresh',id_token:'signed-id',expires_in:3600});
  };
  const oauth = new CodexOAuth(fake as typeof fetch, async (token) => {assert.equal(token,'signed-id');return identity;}, () => 1000);
  const pending = await oauth.start();
  assert.equal(pending.expiresAt, 1900); assert.equal(pending.interval, 5);
  const result = await oauth.poll(pending);
  assert.equal(result?.accountId, 'synthetic-account'); assert.equal(result?.expiresAt, 4600);
  assert.ok(calls.every(url => url.startsWith('https://auth.openai.com/')));
});

test('pending device auth does not exchange codes; invalid identity is rejected', async () => {
  const pending = {deviceId:'device', userCode:'code',interval:5,expiresAt:1900,nextPollAt:1000};
  const oauth = new CodexOAuth((async () => new Response('',{status:403})) as typeof fetch, async()=>identity, ()=>1000);
  assert.equal(await oauth.poll(pending), null);
  const fake = async (input: RequestInfo | URL) => String(input).endsWith('/deviceauth/token')
    ? Response.json({authorization_code:'code',code_verifier:'verifier'})
    : Response.json({access_token:'token',refresh_token:'refresh',id_token:'bad'});
  const invalid = new CodexOAuth(fake as typeof fetch, async()=>{throw new Error('bad signature');}, ()=>1000);
  await assert.rejects(invalid.poll(pending), /invalid_identity_token/);
  await assert.rejects(oauth.poll({...pending,expiresAt:999}), /login_expired/);
});

test('refresh rotates tokens and rejects another account identity', async () => {
  const fake = async (_: RequestInfo | URL, init?: RequestInit) => {
    assert.equal(JSON.parse(String(init?.body)).refresh_token, 'old-refresh');
    return Response.json({access_token:'new-token',refresh_token:'new-refresh',id_token:'signed',expires_in:1800});
  };
  const oauth = new CodexOAuth(fake as typeof fetch, async()=>identity, ()=>1000);
  const next = await oauth.renew(previous);
  assert.equal(next.refreshToken, 'new-refresh'); assert.equal(next.expiresAt, 2800);
  const wrong = new CodexOAuth(fake as typeof fetch, async()=>({...identity,sub:'wrong-person'}), ()=>1000);
  await assert.rejects(wrong.renew(previous), /account_mismatch/);
});
