export interface ReconnectPolicyOptions {
  readonly random?: () => number;
  readonly baseDelayMs?: number;
  readonly maxAttempts?: number;
}

export class BoundedReconnectPolicy {
  private readonly random: () => number;
  private readonly baseDelayMs: number;
  private readonly maxAttempts: number;

  constructor(options: ReconnectPolicyOptions = {}) {
    this.random = options.random ?? Math.random;
    this.baseDelayMs = options.baseDelayMs ?? 1_000;
    this.maxAttempts = options.maxAttempts ?? 3;
  }

  delayForAttempt(attempt: number): number | undefined {
    if (!Number.isInteger(attempt) || attempt < 0 || attempt >= this.maxAttempts) {
      return undefined;
    }
    const jitter = 0.8 + clamp(this.random(), 0, 1) * 0.4;
    return Math.round(this.baseDelayMs * 2 ** attempt * jitter);
  }
}

function clamp(value: number, minimum: number, maximum: number): number {
  if (!Number.isFinite(value)) return 0.5;
  return Math.min(maximum, Math.max(minimum, value));
}
