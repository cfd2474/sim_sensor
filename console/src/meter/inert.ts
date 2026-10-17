import type { Channel } from "../api/endpoints";

/**
 * Inert gas in a leak (nitrogen, argon, helium…): no monitor measures it; it takes the place of air,
 * so O₂ falls in proportion. Its amount is the share of air, in %vol. Mirrors InertGas in the
 * shared code.
 */
export const INERT_ID = "INERT";
export const INERT_LABEL = "Inert gas (N₂, Ar, He…)";
export const INERT_MAX_PERCENT = 100;
/** A new inert leak: 20 % of the air, so O₂ reads about 16.7, well under A1. */
export const DEFAULT_INERT_PERCENT = 20;
const AIR_O2 = 20.9;

/** O₂ where inert gas makes up [percent] of the air (the exercise's O₂ baseline, or 20.9). */
export function o2WithInert(channels: Channel[], percent: number): number {
  const baseline = channels.find((c) => c.lowAlarmFalling)?.baseline ?? AIR_O2;
  const share = Math.min(Math.max(percent, 0), INERT_MAX_PERCENT) / 100;
  return Math.round(baseline * (1 - share) * 10) / 10;
}

/** Why an amount can't be saved, or null. */
export function inertProblem(percent: number): string | null {
  return Number.isFinite(percent) && percent > 0 && percent <= INERT_MAX_PERCENT ? null : `Enter more than 0 and at most ${INERT_MAX_PERCENT} %vol.`;
}
