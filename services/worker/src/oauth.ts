import { createRemoteJWKSet, decodeJwt, jwtVerify, type JWTPayload } from 'jose';

export const CODEX_CLIENT_ID = 'app_EMoamEEZ73f0CkXaXp7hrann';
const ISSUER = 'https://auth.openai.com';
const jwks = createRemoteJWKSet(new URL(`${ISSUER}/.well-known/jwks.json`), { timeoutDuration: 10000 });
export type TokenRecord = { accessToken: string; refreshToken: string; accountId: string; subject: string; expiresAt: number };
export type PendingLogin = { deviceId: string; userCode: string; interval: number; expiresAt: number; nextPollAt: number };
export type IdVerifier = (token: string) => Promise<JWTPayload>;

export async function readLimitedJson(response: Response, max = 131072): Promise<Record<string, unknown>> {
  if (!response.body) throw new Error('invalid_upstream_response');
  const reader = response.body.getReader();
  const bytes: number[] = [];
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (bytes.length + value.length > max) { await reader.cancel(); throw new Error('upstream_response_too_large'); }
    for (const byte of value) bytes.push(byte);
  }
  try {
    const value = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(new Uint8Array(bytes)));
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error();
    return value;
  } catch { throw new Error('invalid_upstream_response'); }
}

function requiredString(value: unknown): string {
  if (typeof value !== 'string' || !value || value.length > 32768 || /[\r\n]/.test(value)) throw new Error('invalid_token_response');
  return value;
}

export class CodexOAuth {
  constructor(private fetcher: typeof fetch = fetch, private verifyId: IdVerifier = async (token) =>
    (await jwtVerify(token, jwks, { issuer: ISSUER, audience: CODEX_CLIENT_ID, clockTolerance: 5 })).payload,
    private now = () => Math.floor(Date.now() / 1000)) {}

  private async request(path: string, body: object | URLSearchParams): Promise<Response> {
    try {
      return await this.fetcher.call(globalThis, `${ISSUER}${path}`, {
        method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(15000),
        headers: { 'Content-Type': body instanceof URLSearchParams ? 'application/x-www-form-urlencoded' : 'application/json', Accept: 'application/json' },
        body: body instanceof URLSearchParams ? body.toString() : JSON.stringify(body),
      });
    } catch (error) {
      if (error instanceof Error && error.message.includes('Illegal invocation')) throw new Error('login_runtime_binding_error');
      throw new Error('login_network_error');
    }
  }

  async start(): Promise<PendingLogin> {
    const response = await this.request('/api/accounts/deviceauth/usercode', { client_id: CODEX_CLIENT_ID });
    if (!response.ok) { await response.body?.cancel(); throw new Error(response.status === 403 ? 'device_login_not_enabled' : 'login_start_failed'); }
    const value = await readLimitedJson(response);
    const interval = Number(value.interval ?? 5);
    if (!Number.isFinite(interval)) throw new Error('invalid_login_response');
    const now = this.now();
    return { deviceId: requiredString(value.device_auth_id), userCode: requiredString(value.user_code ?? value.usercode),
      interval: Math.min(30, Math.max(5, Math.ceil(interval))), expiresAt: now + 900, nextPollAt: now + 5 };
  }

  async poll(pending: PendingLogin): Promise<TokenRecord | null> {
    if (this.now() >= pending.expiresAt) throw new Error('login_expired');
    const response = await this.request('/api/accounts/deviceauth/token', { device_auth_id: pending.deviceId, user_code: pending.userCode });
    if (response.status === 403 || response.status === 404) { await response.body?.cancel(); return null; }
    if (!response.ok) { await response.body?.cancel(); throw new Error('login_poll_failed'); }
    const value = await readLimitedJson(response);
    const params = new URLSearchParams({ grant_type: 'authorization_code', client_id: CODEX_CLIENT_ID,
      code: requiredString(value.authorization_code), code_verifier: requiredString(value.code_verifier), redirect_uri: `${ISSUER}/deviceauth/callback` });
    return this.exchange(params);
  }

  async renew(previous: TokenRecord): Promise<TokenRecord> {
    const result = await this.exchange({ client_id: CODEX_CLIENT_ID, grant_type: 'refresh_token', refresh_token: previous.refreshToken }, previous);
    if (result.accountId !== previous.accountId || result.subject !== previous.subject) throw new Error('account_mismatch');
    return result;
  }

  private async exchange(params: object | URLSearchParams, previous?: TokenRecord): Promise<TokenRecord> {
    const response = await this.request('/oauth/token', params);
    if (!response.ok) { await response.body?.cancel(); throw new Error('reauthorization_required'); }
    const value = await readLimitedJson(response);
    const accessToken = requiredString(value.access_token);
    const refreshToken = value.refresh_token == null && previous ? previous.refreshToken : requiredString(value.refresh_token);
    let accountId = previous?.accountId, subject = previous?.subject;
    if (value.id_token != null) {
      let claims: JWTPayload;
      try { claims = await this.verifyId(requiredString(value.id_token)); } catch { throw new Error('invalid_identity_token'); }
      const auth = claims['https://api.openai.com/auth'];
      if (!auth || typeof auth !== 'object') throw new Error('account_id_missing');
      accountId = requiredString((auth as Record<string, unknown>).chatgpt_account_id);
      subject = requiredString(claims.sub);
    }
    if (!accountId || !subject) throw new Error('account_id_missing');
    let seconds = typeof value.expires_in === 'number' ? value.expires_in : 3600;
    // The access token comes directly from the fixed TLS token endpoint. Decode
    // only its expiry; identity comes from the separately verified ID token.
    try { const exp = decodeJwt(accessToken).exp; if (exp) seconds = Math.min(seconds, exp - this.now()); } catch { /* Opaque access tokens are valid. */ }
    if (!Number.isFinite(seconds) || seconds <= 0) throw new Error('expired_token_response');
    return { accessToken, refreshToken, accountId, subject, expiresAt: this.now() + Math.min(86400, Math.floor(seconds)) };
  }
}

function decodeBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
}
function encodeBase64(bytes: Uint8Array): string {
  return btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(''));
}
async function vaultKey(secret: string): Promise<CryptoKey> {
  const bytes = decodeBase64(secret);
  if (bytes.length !== 32) throw new Error('configure_vault_key');
  return crypto.subtle.importKey('raw', bytes, 'AES-GCM', false, ['encrypt', 'decrypt']);
}
export async function seal(value: unknown, secret: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: new TextEncoder().encode('codex-meter-vault-v1') },
    await vaultKey(secret), new TextEncoder().encode(JSON.stringify(value)));
  return JSON.stringify({ v: 1, iv: encodeBase64(iv), ciphertext: encodeBase64(new Uint8Array(ciphertext)) });
}
export async function unseal<T>(raw: string, secret: string): Promise<T> {
  const value = JSON.parse(raw);
  if (value.v !== 1) throw new Error('invalid_vault_record');
  const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: decodeBase64(value.iv), additionalData: new TextEncoder().encode('codex-meter-vault-v1') },
    await vaultKey(secret), decodeBase64(value.ciphertext));
  return JSON.parse(new TextDecoder().decode(plaintext));
}
