import { DurableObject } from 'cloudflare:workers';
import { AccountService, CodexOAuth, fetchExperimentalUsage, type VaultStorage } from './account-service';
import type { Env } from './index';

export class AccountVault extends DurableObject<Env> {
  private service: AccountService;
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS vault_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)');
    const storage: VaultStorage = {
      get: (key) => [...ctx.storage.sql.exec<{ value: string }>('SELECT value FROM vault_state WHERE key = ?', key)][0]?.value ?? null,
      put: (key, value) => { ctx.storage.sql.exec('INSERT INTO vault_state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value', key, value); },
      remove: (key) => { ctx.storage.sql.exec('DELETE FROM vault_state WHERE key = ?', key); },
    };
    this.service = new AccountService(storage, env.VAULT_KEY ?? '', {
      oauth: new CodexOAuth(), usage: fetchExperimentalUsage, now: () => Math.floor(Date.now() / 1000),
      publish: async (snapshot) => {
        if (snapshot) await env.SNAPSHOTS.put('latest', JSON.stringify(snapshot));
        else await env.SNAPSHOTS.delete('latest');
      },
    });
  }
  status() { return this.service.status(); }
  async start() {
    const result = await this.service.start();
    await this.ctx.storage.setAlarm(Date.now() + result.interval * 1000);
    return result;
  }
  poll() { return this.service.poll(); }
  refresh(force = false) { return this.service.refresh(force); }
  cached() { return this.service.cached(); }
  createPairCode() { return this.service.createPairCode(); }
  consumePairCode(code: string) { return this.service.consumePairCode(code); }
  async logout() { const result = await this.service.logout(); await this.ctx.storage.deleteAlarm(); return result; }
  async alarm() {
    try {
      const status = await this.service.status();
      if (status.loginPending) await this.service.poll();
      else if (status.connected) await this.service.refresh();
    } catch { /* Error state is stored by the account service; never log token responses. */ }
    const status = await this.service.status();
    if (status.loginPending || status.connected) {
      await this.ctx.storage.setAlarm(Date.now() + (status.loginPending ? 10000 : 300000));
    }
  }
}
