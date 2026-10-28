import { CodexOAuth, seal, unseal, type PendingLogin, type TokenRecord } from './oauth';
import { fetchExperimentalUsage } from './experimental-codex';
import { validateSnapshot, type Snapshot } from './protocol';

export interface VaultStorage {
  get(key: string): string | null;
  put(key: string, value: string): void;
  remove(key: string): void;
}
export interface AccountDeps {
  oauth: Pick<CodexOAuth, 'start' | 'poll' | 'renew'>;
  usage: (token: string, accountId: string) => Promise<Snapshot>;
  publish: (snapshot: Snapshot | null) => Promise<void>;
  now: () => number;
}
const SAFE_ERRORS = new Set(['device_login_not_enabled', 'login_start_failed', 'login_poll_failed', 'login_expired',
  'login_network_error', 'login_runtime_binding_error', 'reauthorization_required', 'account_mismatch', 'account_id_missing', 'invalid_identity_token',
  'upstream_unavailable', 'upstream_forbidden', 'upstream_access_challenge', 'upstream_rate_limited', 'invalid_upstream_response', 'usage_unavailable']);
export function safeError(error: unknown): string {
  return error instanceof Error && SAFE_ERRORS.has(error.message) ? error.message : 'service_unavailable';
}

export class AccountService {
  private tail: Promise<unknown> = Promise.resolve();
  constructor(private storage: VaultStorage, private key: string, private deps: AccountDeps) {}
  private run<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.tail.then(operation, operation);
    this.tail = next.catch(() => undefined);
    return next;
  }
  private async read<T>(key: string): Promise<T | null> {
    const raw = this.storage.get(key);
    return raw == null ? null : unseal<T>(raw, this.key);
  }
  private async write(key: string, value: unknown) { this.storage.put(key, await seal(value, this.key)); }
  status() { return this.run(async () => {
    const pending = await this.read<PendingLogin>('pending');
    const raw = this.storage.get('snapshot');
    return { connected: this.storage.get('tokens') !== null, loginPending: !!pending && pending.expiresAt > this.deps.now(),
      collectedAt: raw ? JSON.parse(raw).collectedAt : null, error: this.storage.get('lastError') };
  }); }
  start() { return this.run(async () => {
    let pending = await this.read<PendingLogin>('pending');
    if (!pending || pending.expiresAt <= this.deps.now()) {
      const last = Number(this.storage.get('lastStart') ?? 0);
      if (this.deps.now() - last < 60) throw new Error('login_start_failed');
      this.storage.put('lastStart', String(this.deps.now()));
      pending = await this.deps.oauth.start(); await this.write('pending', pending);
    }
    return { verificationUrl: 'https://auth.openai.com/codex/device', userCode: pending.userCode,
      expiresAt: pending.expiresAt, interval: pending.interval };
  }); }
  poll() { return this.run(async () => {
    const pending = await this.read<PendingLogin>('pending');
    if (!pending) return { state: this.storage.get('tokens') ? 'connected' : 'disconnected' };
    if (pending.expiresAt <= this.deps.now()) { this.storage.remove('pending'); throw new Error('login_expired'); }
    if (pending.nextPollAt > this.deps.now()) return { state: 'pending' };
    pending.nextPollAt = this.deps.now() + pending.interval;
    await this.write('pending', pending);
    const tokens = await this.deps.oauth.poll(pending);
    if (!tokens) return { state: 'pending' };
    await this.write('tokens', tokens);
    this.storage.remove('pending'); this.storage.remove('snapshot'); this.storage.remove('lastError');
    await this.deps.publish(null);
    try { await this.update(true); } catch (error) { this.storage.put('lastError', safeError(error)); }
    return { state: 'connected' };
  }); }
  refresh(force = false) { return this.run(async () => {
    try { return await this.update(force); }
    catch (error) { this.storage.put('lastError', safeError(error)); throw new Error(safeError(error)); }
  }); }
  private async update(force: boolean): Promise<Snapshot | null> {
    const raw = this.storage.get('snapshot');
    const previous = raw ? validateSnapshot(JSON.parse(raw), this.deps.now()) : null;
    let tokens = await this.read<TokenRecord>('tokens');
    if (!tokens) return previous;
    const age = previous ? this.deps.now() - previous.collectedAt : Infinity;
    if (age < (force ? 15 : 300)) return previous;
    const lastError = this.storage.get('lastError');
    if (lastError && this.deps.now() - Number(this.storage.get('lastAttempt') ?? 0) < 15) throw new Error(lastError);
    this.storage.put('lastAttempt', String(this.deps.now()));
    if (tokens.expiresAt <= this.deps.now() + 60) { tokens = await this.deps.oauth.renew(tokens); await this.write('tokens', tokens); }
    let snapshot: Snapshot;
    try { snapshot = await this.deps.usage(tokens.accessToken, tokens.accountId); }
    catch (error) {
      if (!(error instanceof Error) || error.message !== 'reauthorization_required') throw error;
      tokens = await this.deps.oauth.renew(tokens); await this.write('tokens', tokens);
      snapshot = await this.deps.usage(tokens.accessToken, tokens.accountId);
    }
    snapshot = validateSnapshot(snapshot, this.deps.now());
    this.storage.put('snapshot', JSON.stringify(snapshot)); this.storage.remove('lastError');
    await this.deps.publish(snapshot);
    return snapshot;
  }
  logout() { return this.run(async () => {
    // Revoke cloud-held access by deleting credentials. Revoke the Codex session
    // separately in ChatGPT security settings if desired; do not revoke other clients.
    this.storage.remove('tokens'); this.storage.remove('pending'); this.storage.remove('snapshot'); this.storage.remove('lastError'); this.storage.remove('pairing');
    await this.deps.publish(null);
    return { ok: true };
  }); }
  cached() { return this.run(async () => {
    const raw = this.storage.get('snapshot');
    return raw ? validateSnapshot(JSON.parse(raw), this.deps.now()) : null;
  }); }
  createPairCode() { return this.run(async () => {
    const alphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    const random = crypto.getRandomValues(new Uint8Array(12));
    const code = Array.from(random, byte => alphabet[byte % alphabet.length]).join('');
    const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(code))), x=>x.toString(16).padStart(2,'0')).join('');
    this.storage.put('pairing', JSON.stringify({hash,expiresAt:this.deps.now()+300,attempts:0}));
    return { code: code.slice(0,6)+'-'+code.slice(6), expiresAt: this.deps.now()+300 };
  }); }
  consumePairCode(code: string) { return this.run(async () => {
    const raw = this.storage.get('pairing');
    if (!raw) return false;
    const record = JSON.parse(raw);
    if (record.expiresAt <= this.deps.now() || record.attempts >= 10) { this.storage.remove('pairing');return false; }
    record.attempts++;this.storage.put('pairing',JSON.stringify(record));
    const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(code.replaceAll('-','').toUpperCase()))),x=>x.toString(16).padStart(2,'0')).join('');
    let mismatch=0;for(let i=0;i<hash.length;i++)mismatch|=hash.charCodeAt(i)^record.hash.charCodeAt(i);
    if(mismatch!==0)return false;
    this.storage.remove('pairing');return true;
  }); }
}

export { CodexOAuth, fetchExperimentalUsage };
