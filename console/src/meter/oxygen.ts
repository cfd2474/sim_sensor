import type { Channel } from "../api/endpoints";

/**
 * O₂ in leaks (operator, 2026-10-17): a leak lowers O₂ below its baseline (depletion, a negative
 * amount) or raises it above (enrichment, positive, at most +5 %vol in all). Mirrors
 * SourceValidator / SourceModel in the shared code.
 */
export const MAX_O2_ENRICHMENT = 5;

/** The channel whose alarm falls (O₂): its leak amounts are signed. */
export function isOxygen(channel: Channel | undefined): boolean {
  return !!channel?.lowAlarmFalling;
}

/** What the meter shows at the source for a leak amount: clamped to its range, "over" above it. */
export function oxygenAtSource(channel: Channel, amount: number): { reading: number; overRange: boolean } {
  const raw = channel.baseline + amount;
  return { reading: Math.round(Math.min(Math.max(raw, channel.rangeMin), channel.rangeMax) * 10) / 10, overRange: raw > channel.rangeMax };
}

/** A new O₂ leak: a drop well under A1 (16.0 %vol with the usual 19.5 A1), so it clearly alarms. */
export function defaultOxygenAmount(channel: Channel): number {
  const target = (channel.lowAlarm ?? channel.baseline - 1.4) - 3.5;
  return Math.round((Math.max(target, channel.rangeMin) - channel.baseline) * 10) / 10;
}

/** Why an amount can't be saved, or null. */
export function oxygenProblem(channel: Channel, amount: number): string | null {
  if (!Number.isFinite(amount) || amount === 0) return "Enter how far below or above the baseline.";
  if (amount < -channel.baseline) return `It can drop at most ${channel.baseline} (to a reading of 0).`;
  if (amount > MAX_O2_ENRICHMENT) return `It can rise at most ${MAX_O2_ENRICHMENT} above the baseline.`;
  return null;
}

/** "4.9 below baseline" / "2 above baseline", for lists. */
export function oxygenAmountText(amount: number): string {
  const size = Math.round(Math.abs(amount) * 10) / 10;
  return `${size} ${amount < 0 ? "below" : "above"} baseline`;
}
