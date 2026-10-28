import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AccountService, type VaultStorage } from '../src/account-service';
import { seal, unseal, type TokenRecord } from '../src/oauth';

const key = Buffer.alloc(32, 3).toString('base64');
const original: TokenRecord = {accessToken:'old-private-access',refreshToken:'old-private-refresh',accountId:'account',subject:'subject',expiresAt:900};
function setup() {
  const data = new Map<string,string>();
  const storage: VaultStorage = { get: key=>data.get(key)??null, put:(key,value)=>{data.set(key,value);}, remove:key=>{data.delete(key);} };
  let now = 1000, renewals = 0, reads = 0, polls = 0;
  const deps = {
    now: ()=>now,
    oauth: {
      start: async()=>({deviceId:'sensitive-device',userCode:'ABCD',interval:5,expiresAt:1900,nextPollAt:1005}),
      poll: async()=>{polls++;return {...original,expiresAt:4600};},
      renew: async()=>{renewals++;await new Promise(resolve=>setTimeout(resolve,5));return {...original,accessToken:'new-private-access',refreshToken:'new-private-refresh',expiresAt:4600};},
    },
    usage: async()=>{reads++;return {schemaVersion:1 as const,collectedAt:now,expiresAt:now+900,windows:{fiveHour:null,weekly:null}};},
    publish: async (_: unknown)=>{},
  };
  return {data,storage,deps,service:new AccountService(storage,key,deps),advance:(seconds:number)=>{now+=seconds;},counts:()=>({renewals,reads,polls})};
}

test('concurrent refresh serializes token rotation and persists the replacement before reuse', async () => {
  const s=setup();s.data.set('tokens',await seal(original,key));
  const results=await Promise.all(Array.from({length:20},()=>s.service.refresh()));
  assert.equal(s.counts().renewals,1);assert.equal(s.counts().reads,1);assert.equal(results.length,20);
  const tokens=await unseal<TokenRecord>(s.data.get('tokens')!,key);
  assert.equal(tokens.refreshToken,'new-private-refresh');
  assert.ok(![...s.data.values()].join('').includes('new-private-access'));
  const restarted=new AccountService(s.storage,key,s.deps);
  await restarted.refresh();assert.equal(s.counts().reads,1);
  const status=await restarted.status();assert.ok(status.connected);assert.ok(!JSON.stringify(status).includes('private'));
});

test('pending login is encrypted and polling interval is enforced', async () => {
  const s=setup();const pending=await s.service.start();assert.equal(pending.userCode,'ABCD');
  assert.ok(!s.data.get('pending')!.includes('sensitive-device'));
  assert.equal((await s.service.poll()).state,'pending');assert.equal(s.counts().polls,0);
  s.advance(5);assert.equal((await s.service.poll()).state,'connected');assert.equal(s.counts().polls,1);
  assert.equal(s.data.has('pending'),false);assert.equal((await s.service.status()).connected,true);
});

test('refresh failure preserves original snapshot timestamp and does not leak errors', async () => {
  const s=setup();s.data.set('tokens',await seal({...original,expiresAt:4600},key));
  await s.service.refresh();const saved=s.data.get('snapshot');s.advance(301);
  s.deps.usage=async()=>{throw new Error('a secret server response');};
  await assert.rejects(s.service.refresh(), /^Error: service_unavailable$/);
  assert.equal(s.data.get('snapshot'),saved);assert.equal((await s.service.status()).error,'service_unavailable');
});

test('logout removes cloud credentials and cached data', async () => {
  const s=setup();s.data.set('tokens',await seal(original,key));await s.service.refresh();
  await s.service.logout();assert.equal((await s.service.status()).connected,false);
  assert.equal(s.data.has('tokens'),false);assert.equal(s.data.has('snapshot'),false);
});

test('pairing is one-time, expires, and is deleted on logout', async () => {
  const s=setup();const first=await s.service.createPairCode();
  assert.ok(!s.data.get('pairing')!.includes(first.code.replace('-','')));
  assert.equal(await s.service.consumePairCode('222222-222222'),false);
  assert.equal(await s.service.consumePairCode(first.code),true);
  assert.equal(await s.service.consumePairCode(first.code),false);
  const second=await s.service.createPairCode();s.advance(301);
  assert.equal(await s.service.consumePairCode(second.code),false);
  const third=await s.service.createPairCode();await s.service.logout();
  assert.equal(await s.service.consumePairCode(third.code),false);
});

test('pairing attempt budget prevents unbounded guesses', async () => {
  const s=setup();const code=await s.service.createPairCode();
  for(let i=0;i<10;i++)assert.equal(await s.service.consumePairCode('222222-222222'),false);
  assert.equal(await s.service.consumePairCode(code.code),false);
});
