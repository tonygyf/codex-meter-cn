export interface QuotaWindow {
  usedPercent: number;
  windowMinutes: number;
  resetsAt: number | null;
}
export interface Snapshot {
  schemaVersion: 1;
  collectedAt: number;
  expiresAt: number;
  windows: { fiveHour: QuotaWindow | null; weekly: QuotaWindow | null };
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function keys(value: Record<string, unknown>, expected: string[]): boolean {
  return Object.keys(value).sort().join(',') === [...expected].sort().join(',');
}
const timestamp = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) > 0;
export function validateSnapshot(value: unknown, now = Math.floor(Date.now() / 1000)): Snapshot {
  if (!object(value) || !keys(value, ['schemaVersion', 'collectedAt', 'expiresAt', 'windows']) ||
      value.schemaVersion !== 1 || !timestamp(value.collectedAt) || !timestamp(value.expiresAt) ||
      value.collectedAt > now + 60 || value.expiresAt <= value.collectedAt ||
      value.expiresAt - value.collectedAt > 3600 || !object(value.windows) ||
      !keys(value.windows, ['fiveHour', 'weekly'])) throw new Error('invalid_snapshot');
  for (const [name, minutes] of [['fiveHour', 300], ['weekly', 10080]] as const) {
    const window = value.windows[name];
    if (window === null) continue;
    if (!object(window) || !keys(window, ['usedPercent', 'windowMinutes', 'resetsAt']) ||
        typeof window.usedPercent !== 'number' || !Number.isFinite(window.usedPercent) ||
        window.usedPercent < 0 || window.usedPercent > 100 || window.windowMinutes !== minutes ||
        !(window.resetsAt === null || timestamp(window.resetsAt))) throw new Error('invalid_window');
  }
  return value as unknown as Snapshot;
}
